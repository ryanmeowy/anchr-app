package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.application.acl.IngestionDoclingAcl;
import com.anchr.core.ingestion.application.acl.IngestionStorageAcl;
import com.anchr.core.ingestion.application.model.IngestionDoclingException;
import com.anchr.core.ingestion.application.model.IngestionDoclingFailureKind;
import com.anchr.core.ingestion.application.model.IngestionDoclingJob;
import com.anchr.core.ingestion.application.model.IngestionDoclingJobError;
import com.anchr.core.ingestion.application.model.IngestionStorageCredential;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.kb.domain.model.Asset;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
final class IngestionParseStage {
    private final IngestionObjectStoragePort objectStoragePort;
    private final IngestionStorageAcl ingestionStorageAcl;
    private final DoclingChunkMapper doclingChunkMapper;
    private final IngestionDoclingAcl ingestionDoclingAcl;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    IngestionParseStage(IngestionObjectStoragePort objectStoragePort,
                        IngestionStorageAcl ingestionStorageAcl,
                        DoclingChunkMapper doclingChunkMapper,
                        IngestionDoclingAcl ingestionDoclingAcl,
                        AesUtil aesUtil,
                        ObjectMapper objectMapper) {
        this.objectStoragePort = objectStoragePort;
        this.ingestionStorageAcl = ingestionStorageAcl;
        this.doclingChunkMapper = doclingChunkMapper;
        this.ingestionDoclingAcl = ingestionDoclingAcl;
        this.aesUtil = aesUtil;
        this.objectMapper = objectMapper;
    }

    ParseRunContext createContext(IngestionTaskItem item,
                                  Asset asset,
                                  boolean embeddedImageUploadEnabled) {
        long generation = requireTargetIndexGeneration(item);
        IngestionStorageTarget storageTarget = embeddedImageUploadEnabled
                ? ingestionStorageAcl.findTarget(asset.getId(), generation).orElse(null)
                : null;
        IngestionParseRequestTemplate template = IngestionParseRequestTemplate.capture(
                asset, embeddedImageUploadEnabled, storageTarget).validated();
        return new ParseRunContext(
                IngestionParseIdentity.requestId(item.getTaskId(), item.getId(), generation),
                IngestionParseIdentity.sourceRevision(asset),
                asset.getId(),
                generation,
                template);
    }

    ParsedJob parse(ParseRunContext context,
                    Asset asset,
                    Duration timeout,
                    Duration pollInterval,
                    int providerMaxRetries) {
        Instant deadline = Instant.now().plus(timeout);
        int recoveries = 0;
        String jobId = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                IngestionDoclingJob job;
                if (!StringUtils.hasText(jobId)) {
                    job = ingestionDoclingAcl.submitJob(buildParseRequest(context, asset));
                    jobId = job.jobId();
                } else {
                    job = ingestionDoclingAcl.getJob(jobId, context.requestId());
                }
                switch (job.normalizedStatus()) {
                    case "succeeded" -> {
                        if (job.result() == null) {
                            throw new BusinessException(
                                    ApiError.TEXT_PARSE_FAILED,
                                    "Docling succeeded without a parse result.");
                        }
                        return new ParsedJob(jobId, job.result());
                    }
                    case "queued", "running" -> sleep(pollInterval);
                    case "failed" -> {
                        if (!isRetryable(job.error()) || recoveries >= providerMaxRetries) {
                            throw doclingJobFailure(job.error());
                        }
                        acknowledgeFailedJob(jobId);
                        jobId = null;
                        recoveries++;
                        sleep(retryDelay(recoveries));
                    }
                    default -> throw new BusinessException(
                            ApiError.TEXT_PARSE_FAILED,
                            "Docling returned unknown job status: " + clip(job.status(), 128));
                }
            } catch (IngestionDoclingException failure) {
                if (failure.kind() == IngestionDoclingFailureKind.NOT_FOUND
                        && recoveries < providerMaxRetries) {
                    jobId = null;
                    recoveries++;
                    sleep(retryDelay(recoveries));
                    continue;
                }
                if (failure.kind() == IngestionDoclingFailureKind.TRANSIENT
                        && recoveries < providerMaxRetries) {
                    recoveries++;
                    sleep(positiveDuration(failure.retryAfter())
                            ? failure.retryAfter() : retryDelay(recoveries));
                    continue;
                }
                throw new BusinessException(
                        ApiError.TEXT_PARSE_FAILED, failure.getMessage(), failure);
            }
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED,
                "Docling parse exceeded " + timeout + ".");
    }

    ParsedChunks mapChunks(IngestionTaskItem item, Asset asset, ParseResponse parsed) {
        boolean parsedContentIsEmpty = parsed.chunks() == null || parsed.chunks().isEmpty();
        long generation = requireTargetIndexGeneration(item);
        List<Chunk> chunks = doclingChunkMapper.toTextChunks(asset, parsed, generation);
        if (chunks == null) chunks = List.of();
        List<Chunk> embeddedImages = doclingChunkMapper.toDocumentImageChunks(
                asset, parsed, generation);
        if (!embeddedImages.isEmpty()) {
            List<Chunk> combined = new ArrayList<>(chunks.size() + embeddedImages.size());
            combined.addAll(chunks);
            combined.addAll(embeddedImages);
            chunks = List.copyOf(combined);
        }
        if (chunks.isEmpty() && !isImage(asset)) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED,
                    parsedContentIsEmpty
                            ? "Docling returned no usable text or embedded images."
                            : "Docling returned no usable chunks.");
        }
        return new ParsedChunks(generation, List.copyOf(chunks));
    }

    void acknowledgeBestEffort(String jobId) {
        if (!StringUtils.hasText(jobId)) return;
        try {
            ingestionDoclingAcl.ackJob(jobId);
        } catch (RuntimeException exception) {
            log.warn("Docling ACK failed after ingestion terminal state, jobId={}: {}",
                    jobId, exception.getMessage());
        }
    }

    private ParseRequest buildParseRequest(ParseRunContext context, Asset asset) {
        return context.template().toRequest(
                context.requestId(), context.sourceRevision(), resolveSourceUrl(asset),
                buildEncryptedOssCredentials(context));
    }

    private Map<String, String> buildEncryptedOssCredentials(ParseRunContext context) {
        IngestionParseRequestTemplate template = context.template();
        if (template.ossTarget() == null) return null;
        try {
            IngestionStorageCredential credential =
                    ingestionStorageAcl.issueTemporaryCredential(
                            template.ossTarget(),
                            context.assetId(),
                            context.targetGeneration());
            Map<String, Object> token = credential.toCredentialMap();
            String aad = String.join("\n",
                    context.requestId(), template.ossTarget().bucket(),
                    template.ossTarget().basePath(), template.ossTarget().endpoint());
            AesUtil.AeadEnvelope envelope = aesUtil.encryptAead(
                    objectMapper.writeValueAsString(token), aad);
            return Map.of(
                    "version", "1",
                    "keyId", "app-security-v1",
                    "nonce", envelope.nonce(),
                    "ciphertext", envelope.ciphertext(),
                    "tag", envelope.tag(),
                    "expiration", Objects.toString(token.get("expiration"), ""));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Failed to issue temporary Docling output credentials.", exception);
        }
    }

    private String resolveSourceUrl(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return objectStoragePort.buildDownloadUrl(asset.getObjectKey());
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no source object key.");
    }

    private long requireTargetIndexGeneration(IngestionTaskItem item) {
        Long generation = item.getTargetIndexGeneration();
        if (generation == null || generation < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return generation;
    }

    private boolean isRetryable(IngestionDoclingJobError error) {
        if (error == null || !StringUtils.hasText(error.code())) return false;
        String code = error.code().trim().toUpperCase(Locale.ROOT);
        return "QUEUE_TIMEOUT".equals(code)
                || "INTERNAL_ERROR".equals(code)
                || "SOURCE_DOWNLOAD_ERROR".equals(code);
    }

    private BusinessException doclingJobFailure(IngestionDoclingJobError error) {
        String message = error == null
                ? "Docling job failed."
                : "Docling job failed [" + clip(error.code(), 80) + "]: "
                        + clip(error.message(), 300);
        return new BusinessException(ApiError.TEXT_PARSE_FAILED, message);
    }

    private void acknowledgeFailedJob(String jobId) {
        try {
            ingestionDoclingAcl.ackJob(jobId);
        } catch (IngestionDoclingException exception) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED,
                    "Failed to release the unsuccessful Docling job: " + exception.getMessage(),
                    exception);
        }
    }

    private Duration retryDelay(int retryCount) {
        long seconds = Math.min(60L, 1L << Math.min(Math.max(0, retryCount - 1), 6));
        return Duration.ofSeconds(seconds);
    }

    private void sleep(Duration duration) {
        if (!positiveDuration(duration)) return;
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IngestionWorkerInterruptedException(exception);
        }
    }

    private boolean positiveDuration(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean isImage(Asset asset) {
        return "IMAGE".equalsIgnoreCase(asset.getFileType());
    }

    private String clip(String text, int maxLength) {
        if (!StringUtils.hasText(text)) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    record ParseRunContext(
            String requestId,
            String sourceRevision,
            String assetId,
            long targetGeneration,
            IngestionParseRequestTemplate template) {
    }

    record ParsedJob(String jobId, ParseResponse result) {
    }

    record ParsedChunks(long targetGeneration, List<Chunk> chunks) {
        ParsedChunks {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }
}
