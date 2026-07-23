package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException.Reason;
import com.anchr.core.ingestion.application.artifact.IngestionEmbeddingArtifact.ChunkPayload;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists immutable, versioned checkpoints between ingestion execution stages.
 */
@Service
public class IngestionArtifactStore {

    static final String PARSE_ARTIFACT_TYPE = "anchr.ingestion.parse-result";
    static final String EMBEDDING_ARTIFACT_TYPE = "anchr.ingestion.embedding-result";
    static final int ARTIFACT_VERSION = 1;
    static final int DEFAULT_MAX_COMPRESSED_BYTES = 32 * 1024 * 1024;
    static final int DEFAULT_MAX_UNCOMPRESSED_BYTES = 256 * 1024 * 1024;

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String GZIP_CONTENT_ENCODING = "gzip";

    private final IngestionObjectStoragePort objectStoragePort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxCompressedBytes;
    private final int maxUncompressedBytes;

    public IngestionArtifactStore(IngestionObjectStoragePort objectStoragePort,
                                  ObjectMapper objectMapper) {
        this(objectStoragePort, objectMapper, Clock.systemUTC(),
                DEFAULT_MAX_COMPRESSED_BYTES, DEFAULT_MAX_UNCOMPRESSED_BYTES);
    }

    IngestionArtifactStore(IngestionObjectStoragePort objectStoragePort,
                           ObjectMapper objectMapper,
                           Clock clock,
                           int maxCompressedBytes,
                           int maxUncompressedBytes) {
        if (maxCompressedBytes <= 0 || maxUncompressedBytes <= 0
                || maxCompressedBytes == Integer.MAX_VALUE
                || maxUncompressedBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Artifact size limits must be between 1 and Integer.MAX_VALUE - 1.");
        }
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    /**
     * Store a completed Docling response before acknowledging the Docling job.
     *
     * @return immutable object key that must be fenced into the item row
     */
    public String writeParseResult(IngestionTaskItem item, String jobId, ParseResponse result) {
        requireItemIdentity(item);
        requirePositive(item.getParseAttempt(), "parseAttempt");
        requireText(jobId, "jobId");
        requireText(item.getDoclingRequestId(), "doclingRequestId");
        requireText(item.getSourceRevision(), "sourceRevision");
        if (item.getDoclingJobId() != null && !item.getDoclingJobId().equals(jobId)) {
            throw identityMismatch("Docling job id does not match the claimed item.");
        }
        if (result == null || !item.getDoclingRequestId().equals(result.requestId())) {
            throw identityMismatch("Parse response request id does not match the claimed item.");
        }

        String objectKey = parseObjectKey(item, jobId);
        IngestionParseArtifact artifact = new IngestionParseArtifact(
                PARSE_ARTIFACT_TYPE,
                ARTIFACT_VERSION,
                item.getTaskId(),
                item.getId(),
                item.getKbId(),
                item.getAssetId(),
                item.getParseAttempt(),
                jobId,
                item.getDoclingRequestId(),
                item.getSourceRevision(),
                Instant.now(clock),
                result);

        byte[] compressed = encode(artifact);
        if (putIfAbsent(objectKey, compressed)) {
            return objectKey;
        }

        IngestionParseArtifact existing = readParseArtifact(objectKey);
        validateParseIdentity(item, objectKey, existing);
        if (!Objects.equals(existing.result(), result)) {
            throw immutableConflict("Existing parse artifact has different content.");
        }
        return objectKey;
    }

    /**
     * Load and strictly validate the parse artifact referenced by an item.
     */
    public ParseResponse readParseResult(IngestionTaskItem item) {
        requireItemIdentity(item);
        String objectKey = requireText(item.getParseResultObjectKey(), "parseResultObjectKey");
        IngestionParseArtifact artifact = readParseArtifact(objectKey);
        validateParseIdentity(item, objectKey, artifact);
        return artifact.result();
    }

    /**
     * Store the complete chunk set after embedding, so INDEX can resume without
     * repeating parsing or embedding.
     *
     * @return immutable object key that must be fenced into the item row
     */
    public String writeEmbeddingResult(IngestionTaskItem item, List<Chunk> chunks) {
        requireItemIdentity(item);
        requirePositive(item.getParseAttempt(), "parseAttempt");
        requirePositive(item.getExecutionEpoch(), "executionEpoch");
        requirePositive(item.getStageAttempt(), "stageAttempt");
        requireText(item.getDoclingRequestId(), "doclingRequestId");
        requireText(item.getSourceRevision(), "sourceRevision");
        requireText(item.getParseResultObjectKey(), "parseResultObjectKey");
        List<ChunkPayload> payloads = toPayloads(item, chunks);

        String objectKey = embeddingObjectKey(item);
        IngestionEmbeddingArtifact artifact = new IngestionEmbeddingArtifact(
                EMBEDDING_ARTIFACT_TYPE,
                ARTIFACT_VERSION,
                item.getTaskId(),
                item.getId(),
                item.getKbId(),
                item.getAssetId(),
                item.getParseAttempt(),
                item.getExecutionEpoch(),
                item.getStageAttempt(),
                item.getDoclingRequestId(),
                item.getSourceRevision(),
                item.getParseResultObjectKey(),
                Instant.now(clock),
                payloads);

        byte[] compressed = encode(artifact);
        if (putIfAbsent(objectKey, compressed)) {
            return objectKey;
        }

        IngestionEmbeddingArtifact existing = readEmbeddingArtifact(objectKey);
        validateEmbeddingIdentity(item, objectKey, existing);
        if (!Objects.equals(existing.chunks(), payloads)) {
            throw immutableConflict("Existing embedding artifact has different content.");
        }
        return objectKey;
    }

    /**
     * Load and strictly validate the embedding artifact referenced by an item.
     */
    public List<Chunk> readEmbeddingResult(IngestionTaskItem item) {
        requireItemIdentity(item);
        String objectKey = requireText(item.getEmbeddingResultObjectKey(), "embeddingResultObjectKey");
        IngestionEmbeddingArtifact artifact = readEmbeddingArtifact(objectKey);
        validateEmbeddingIdentity(item, objectKey, artifact);
        return artifact.chunks().stream().map(ChunkPayload::toDomain).toList();
    }

    private List<ChunkPayload> toPayloads(IngestionTaskItem item, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IngestionArtifactException(Reason.CORRUPT,
                    "Embedding artifact requires at least one chunk.");
        }
        try {
            List<ChunkPayload> payloads = chunks.stream()
                    .map(chunk -> {
                        if (chunk == null) {
                            throw new IllegalArgumentException("Embedding artifact contains a null chunk.");
                        }
                        validateChunkIdentity(item, chunk.getKbId(), chunk.getAssetId());
                        return ChunkPayload.fromDomain(chunk);
                    })
                    .toList();
            validateUniqueSegmentIds(payloads);
            return payloads;
        } catch (IngestionArtifactException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IngestionArtifactException(
                    Reason.CORRUPT, "Embedding artifact contains invalid chunk data.", e);
        }
    }

    private byte[] encode(Object artifact) {
        try {
            ByteArrayOutputStream output = new LimitedByteArrayOutputStream(maxCompressedBytes);
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                // Count the JSON bytes before compression while writing directly into gzip.
                // This avoids allocating a second, potentially hundreds-of-megabytes raw JSON
                // buffer before the configured limit can take effect.
                objectMapper.writeValue(
                        new LimitedOutputStream(gzip, maxUncompressedBytes), artifact);
            }
            return output.toByteArray();
        } catch (IngestionArtifactException e) {
            throw e;
        } catch (ArtifactSizeLimitRuntimeException e) {
            throw tooLarge("Artifact exceeds a configured size limit.");
        } catch (JsonProcessingException e) {
            throw new IngestionArtifactException(
                    Reason.CORRUPT, "Failed to serialize ingestion artifact.", e);
        } catch (IOException e) {
            throw new IngestionArtifactException(
                    Reason.CORRUPT, "Failed to compress ingestion artifact.", e);
        }
    }

    private <T> T decode(String objectKey, Class<T> type) {
        byte[] compressed = storageCall(
                () -> objectStoragePort.readArtifact(objectKey, maxCompressedBytes));
        if (compressed == null || compressed.length == 0) {
            throw corrupt("Ingestion artifact is empty.", null);
        }
        if (compressed.length > maxCompressedBytes) {
            throw tooLarge("Artifact exceeds the configured compressed-size limit.");
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] json = gzip.readNBytes(maxUncompressedBytes + 1);
            if (json.length > maxUncompressedBytes) {
                throw tooLarge("Artifact exceeds the configured uncompressed-size limit.");
            }
            return objectMapper.readValue(json, type);
        } catch (IngestionArtifactException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw corrupt("Ingestion artifact is not valid gzip JSON.", e);
        }
    }

    private boolean putIfAbsent(String objectKey, byte[] compressed) {
        return storageCall(() -> objectStoragePort.putArtifactIfAbsent(
                objectKey, compressed, JSON_CONTENT_TYPE, GZIP_CONTENT_ENCODING));
    }

    private IngestionParseArtifact readParseArtifact(String objectKey) {
        IngestionParseArtifact artifact = decode(objectKey, IngestionParseArtifact.class);
        if (artifact == null || artifact.result() == null || artifact.createdAt() == null) {
            throw corrupt("Parse artifact is missing required fields.", null);
        }
        if (!PARSE_ARTIFACT_TYPE.equals(artifact.artifactType())
                || artifact.version() != ARTIFACT_VERSION) {
            throw corrupt("Unsupported parse artifact type or version.", null);
        }
        return artifact;
    }

    private IngestionEmbeddingArtifact readEmbeddingArtifact(String objectKey) {
        IngestionEmbeddingArtifact artifact = decode(objectKey, IngestionEmbeddingArtifact.class);
        if (artifact == null || artifact.createdAt() == null
                || artifact.chunks() == null || artifact.chunks().isEmpty()) {
            throw corrupt("Embedding artifact is missing required fields.", null);
        }
        if (!EMBEDDING_ARTIFACT_TYPE.equals(artifact.artifactType())
                || artifact.version() != ARTIFACT_VERSION) {
            throw corrupt("Unsupported embedding artifact type or version.", null);
        }
        validateUniqueSegmentIds(artifact.chunks());
        return artifact;
    }

    private void validateParseIdentity(IngestionTaskItem item, String objectKey,
                                       IngestionParseArtifact artifact) {
        if (!Objects.equals(item.getTaskId(), artifact.taskId())
                || !Objects.equals(item.getId(), artifact.itemId())
                || !Objects.equals(item.getKbId(), artifact.kbId())
                || !Objects.equals(item.getAssetId(), artifact.assetId())
                || item.getParseAttempt() != artifact.parseAttempt()
                || !Objects.equals(item.getDoclingRequestId(), artifact.requestId())
                || !Objects.equals(item.getSourceRevision(), artifact.sourceRevision())
                || (item.getDoclingJobId() != null
                    && !Objects.equals(item.getDoclingJobId(), artifact.jobId()))
                || !Objects.equals(artifact.requestId(), artifact.result().requestId())
                || !Objects.equals(objectKey, parseObjectKey(artifact))) {
            throw identityMismatch("Parse artifact identity does not match the current item.");
        }
    }

    private void validateEmbeddingIdentity(IngestionTaskItem item, String objectKey,
                                           IngestionEmbeddingArtifact artifact) {
        if (!Objects.equals(item.getTaskId(), artifact.taskId())
                || !Objects.equals(item.getId(), artifact.itemId())
                || !Objects.equals(item.getKbId(), artifact.kbId())
                || !Objects.equals(item.getAssetId(), artifact.assetId())
                || item.getParseAttempt() != artifact.parseAttempt()
                || item.getExecutionEpoch() != artifact.executionEpoch()
                || !Objects.equals(item.getDoclingRequestId(), artifact.requestId())
                || !Objects.equals(item.getSourceRevision(), artifact.sourceRevision())
                || !Objects.equals(item.getParseResultObjectKey(), artifact.parseResultObjectKey())
                || !Objects.equals(objectKey, embeddingObjectKey(artifact))) {
            throw identityMismatch("Embedding artifact identity does not match the current item.");
        }
        for (ChunkPayload chunk : artifact.chunks()) {
            if (chunk == null) {
                throw corrupt("Embedding artifact contains a null chunk.", null);
            }
            validateChunkIdentity(item, chunk.kbId(), chunk.assetId());
        }
    }

    private void validateChunkIdentity(IngestionTaskItem item, String kbId, String assetId) {
        if (!Objects.equals(item.getKbId(), kbId) || !Objects.equals(item.getAssetId(), assetId)) {
            throw identityMismatch("Chunk identity does not match the current item.");
        }
    }

    private void validateUniqueSegmentIds(List<ChunkPayload> chunks) {
        Set<String> segmentIds = new HashSet<>();
        for (ChunkPayload chunk : chunks) {
            if (chunk == null || chunk.segmentId() == null || chunk.segmentId().isBlank()
                    || !segmentIds.add(chunk.segmentId())) {
                throw corrupt("Embedding artifact contains a missing or duplicate segment id.", null);
            }
        }
    }

    private String parseObjectKey(IngestionTaskItem item, String jobId) {
        return "ingestion/" + pathSegment(item.getTaskId(), "taskId")
                + "/" + pathSegment(item.getId(), "itemId")
                + "/parse/" + item.getParseAttempt()
                + "/jobs/" + pathSegment(jobId, "jobId")
                + "/parse-result.v1.json.gz";
    }

    private String parseObjectKey(IngestionParseArtifact artifact) {
        return "ingestion/" + pathSegment(artifact.taskId(), "taskId")
                + "/" + pathSegment(artifact.itemId(), "itemId")
                + "/parse/" + artifact.parseAttempt()
                + "/jobs/" + pathSegment(artifact.jobId(), "jobId")
                + "/parse-result.v1.json.gz";
    }

    private String embeddingObjectKey(IngestionTaskItem item) {
        return "ingestion/" + pathSegment(item.getTaskId(), "taskId")
                + "/" + pathSegment(item.getId(), "itemId")
                + "/execution/" + item.getExecutionEpoch()
                + "/embed/" + item.getStageAttempt()
                + "/embedding-result.v1.json.gz";
    }

    private String embeddingObjectKey(IngestionEmbeddingArtifact artifact) {
        return "ingestion/" + pathSegment(artifact.taskId(), "taskId")
                + "/" + pathSegment(artifact.itemId(), "itemId")
                + "/execution/" + artifact.executionEpoch()
                + "/embed/" + artifact.stageAttempt()
                + "/embedding-result.v1.json.gz";
    }

    private String pathSegment(String value, String name) {
        String segment = requireText(value, name);
        if (".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0
                || segment.chars().anyMatch(Character::isISOControl)) {
            throw new IngestionArtifactException(
                    Reason.IDENTITY_MISMATCH, name + " is not a safe object-key segment.");
        }
        return segment;
    }

    private void requireItemIdentity(IngestionTaskItem item) {
        if (item == null) {
            throw identityMismatch("Ingestion item is required.");
        }
        requireText(item.getTaskId(), "taskId");
        requireText(item.getId(), "itemId");
        requireText(item.getKbId(), "kbId");
        requireText(item.getAssetId(), "assetId");
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw identityMismatch(name + " must not be blank.");
        }
        return value;
    }

    private void requirePositive(long value, String name) {
        if (value < 1) {
            throw identityMismatch(name + " must be positive.");
        }
    }

    private <T> T storageCall(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (IngestionArtifactException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IngestionArtifactException(
                    Reason.STORAGE, "Object storage operation failed for an ingestion artifact.", e);
        }
    }

    private IngestionArtifactException corrupt(String message, Throwable cause) {
        return new IngestionArtifactException(Reason.CORRUPT, message, cause);
    }

    private IngestionArtifactException identityMismatch(String message) {
        return new IngestionArtifactException(Reason.IDENTITY_MISMATCH, message);
    }

    private IngestionArtifactException immutableConflict(String message) {
        return new IngestionArtifactException(Reason.IMMUTABLE_CONFLICT, message);
    }

    private IngestionArtifactException tooLarge(String message) {
        return new IngestionArtifactException(Reason.TOO_LARGE, message);
    }

    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int limit;

        private LimitedByteArrayOutputStream(int limit) {
            super(Math.min(limit, 8192));
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ensureCapacityWithinLimit(length);
            super.write(bytes, offset, length);
        }

        private void ensureCapacityWithinLimit(int additionalBytes) {
            if ((long) count + additionalBytes > limit) {
                throw new ArtifactSizeLimitRuntimeException();
            }
        }
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final int limit;
        private int count;

        private LimitedOutputStream(OutputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacityWithinLimit(1);
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacityWithinLimit(length);
            delegate.write(bytes, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            // ObjectMapper closes its target by default. Keep gzip ownership in encode(),
            // where the footer is also subject to the compressed-size limiter.
            delegate.flush();
        }

        private void ensureCapacityWithinLimit(int additionalBytes) {
            if ((long) count + additionalBytes > limit) {
                throw new ArtifactSizeLimitRuntimeException();
            }
        }
    }

    private static final class ArtifactSizeLimitRuntimeException extends RuntimeException {
    }
}
