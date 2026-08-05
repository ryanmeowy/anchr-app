package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort.EmbeddingSession;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.integration.ai.client.AiClient;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import com.anchr.core.search.domain.model.SegmentType;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class IngestionEmbeddingStage {
    private final Object embeddingPaceLock = new Object();
    private long nextEmbeddingCallAt;

    private final IngestionEmbeddingPort embeddingPort;
    private final IngestionObjectStoragePort objectStoragePort;
    private final IdGen idGen;

    IngestionEmbeddingStage(IngestionEmbeddingPort embeddingPort,
                            IngestionObjectStoragePort objectStoragePort,
                            IdGen idGen) {
        this.embeddingPort = embeddingPort;
        this.objectStoragePort = objectStoragePort;
        this.idGen = idGen;
    }

    PreparedSegments prepare(Asset asset,
                             IngestionParseStage.ParsedChunks parsed,
                             Settings settings) {
        EmbeddingSession session = embeddingPort.openSession();
        if (session == null) {
            session = legacySession();
        }
        Profile profile = Profile.fromMulti(session.isMulti());
        List<IngestionIndexSegment> segments = buildSegments(
                asset, parsed.chunks(), parsed.targetGeneration(), profile);
        String imageInput = EmbeddingProjectionPolicy.requiresImageVisual(
                profile, asset.getFileType()) ? resolveImageEmbeddingUrl(asset) : null;
        return new PreparedSegments(
                applyEmbeddings(
                        asset, segments, profile, imageInput, settings, session),
                session.profileFingerprint());
    }

    private List<IngestionIndexSegment> buildSegments(Asset asset,
                                                      List<Chunk> chunks,
                                                      long generation,
                                                      Profile profile) {
        long createdAt = System.currentTimeMillis();
        List<IngestionIndexSegment> segments = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getSegmentId())) continue;
            SegmentType segmentType = chunk.getSegmentType() != null
                    ? chunk.getSegmentType()
                    : isImage(asset) ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK;
            segments.add(new IngestionIndexSegment(
                    chunk.getSegmentId(), chunk.getKbId(), asset.getId(), generation,
                    asset.getFileType(), segmentType.name(), chunk.getTitle(),
                    chunk.getChunkText(), chunk.getOcrText(), chunk.getPageNo(),
                    chunk.getChunkOrder(), chunk.getBboxInfos(), chunk.getImageWidth(),
                    chunk.getImageHeight(), null, chunk.getSourceRef(), null, null,
                    null, createdAt));
        }
        if (EmbeddingProjectionPolicy.requiresImageVisual(profile, asset.getFileType())) {
            segments.add(new IngestionIndexSegment(
                    idGen.nextIdStr(), asset.getKbId(), asset.getId(), generation,
                    asset.getFileType(), SegmentType.IMAGE_VISUAL.name(),
                    StringUtils.hasText(asset.getTitle()) ? asset.getTitle() : asset.getFileName(),
                    null, null, null, 0, null, null, null, null,
                    stableSourceRef(asset), null, null, null, createdAt));
        }
        return segments;
    }

    private List<IngestionIndexSegment> applyEmbeddings(
            Asset asset,
            List<IngestionIndexSegment> segments,
            Profile profile,
            String imageInput,
            Settings settings,
            EmbeddingSession session) {
        List<IngestionIndexSegment> embedded = new ArrayList<>(segments.size());
        for (IngestionIndexSegment segment : segments) {
            SegmentType segmentType = SegmentType.valueOf(segment.segmentType());
            String imageSource = switch (segmentType) {
                case IMAGE_VISUAL -> imageInput;
                case DOCUMENT_IMAGE -> StringUtils.hasText(segment.sourceRef())
                        ? objectStoragePort.buildImageEmbeddingUrl(segment.sourceRef()) : null;
                default -> null;
            };
            Optional<EmbeddingProjection> projection = EmbeddingProjectionPolicy.select(
                    profile, asset.getFileType(), segmentType,
                    segment.contentText(), segment.ocrText(), imageSource);
            if (projection.isEmpty()) {
                embedded.add(segment.withEmbedding(null));
                continue;
            }
            EmbeddingProjection selected = projection.get();
            List<Float> embedding = embed(
                    selected.source(), selected.inputType().requestValue(), settings, session);
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            embedded.add(segment.withEmbedding(embedding));
        }
        return embedded;
    }

    private List<Float> embed(
            String input,
            String inputType,
            Settings settings,
            EmbeddingSession session
    ) {
        int attempts = Math.max(1, settings.rateLimitMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            reserveEmbeddingCallSlot(settings.minIntervalMs());
            try {
                return session.embed(input, inputType);
            } catch (BusinessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (!isRateLimitError(exception) || attempt == attempts) {
                    throw new IngestionEmbeddingCallException(exception);
                }
                sleep(Duration.ofMillis(resolveEmbeddingBackoffMs(
                        attempt, settings.rateLimitBackoffMs())));
            }
        }
        throw new IllegalStateException("Embedding retry loop terminated unexpectedly.");
    }

    private void reserveEmbeddingCallSlot(long minIntervalMs) {
        long waitMs;
        synchronized (embeddingPaceLock) {
            long now = System.currentTimeMillis();
            waitMs = Math.max(0L, nextEmbeddingCallAt - now);
            nextEmbeddingCallAt = Math.max(now, nextEmbeddingCallAt)
                    + Math.max(0L, minIntervalMs);
        }
        if (waitMs > 0L) sleep(Duration.ofMillis(waitMs));
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiClient.OpenAiException failure
                    && failure.statusCode() == 429) return true;
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (message.contains("429") || message.contains("Throttling")
                        || message.contains("RateQuota") || lower.contains("rate limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long resolveEmbeddingBackoffMs(int attempt, long configuredBackoffMs) {
        long base = Math.max(1000L, configuredBackoffMs);
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 4);
        return base * multiplier;
    }

    private String stableSourceRef(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) return asset.getObjectKey().trim();
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no source object key.");
    }

    private String resolveImageEmbeddingUrl(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return objectStoragePort.buildImageEmbeddingUrl(asset.getObjectKey().trim());
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no source object key.");
    }

    private void sleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return;
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IngestionWorkerInterruptedException(exception);
        }
    }

    private boolean isImage(Asset asset) {
        return "IMAGE".equalsIgnoreCase(asset.getFileType());
    }

    private EmbeddingSession legacySession() {
        return new EmbeddingSession() {
            @Override
            public List<Float> embed(String source, String sourceType) {
                return embeddingPort.embed(source, sourceType);
            }

            @Override
            public boolean isMulti() {
                return embeddingPort.isMulti();
            }

            @Override
            public String profileFingerprint() {
                return "legacy-profile";
            }
        };
    }

    record Settings(long minIntervalMs,
                    int rateLimitMaxAttempts,
                    long rateLimitBackoffMs) {
    }

    record PreparedSegments(
            List<IngestionIndexSegment> segments,
            String embeddingProfileFingerprint
    ) {
    }
}
