package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException.Reason;
import com.anchr.core.ingestion.config.IngestionArtifactProperties;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists immutable, versioned checkpoints between ingestion execution stages.
 */
@Service
public class IngestionArtifactStore {

    static final String PARSE_ARTIFACT_TYPE = "anchr.ingestion.parse-result";
    static final int ARTIFACT_VERSION = 1;
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String GZIP_CONTENT_ENCODING = "gzip";
    private static final String PARSE_REGISTRY_TYPE = "PARSE_RESULT";
    private static final String PRODUCED_PROVENANCE = "PRODUCED";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final IngestionObjectStoragePort objectStoragePort;
    private final ObjectMapper objectMapper;
    private final int maxCompressedBytes;
    private final int maxUncompressedBytes;

    public IngestionArtifactStore(IngestionObjectStoragePort objectStoragePort,
                                  ObjectMapper objectMapper,
                                  IngestionArtifactProperties properties) {
        Objects.requireNonNull(properties);
        int maxCompressedBytes = properties.getMaxCompressedBytes();
        int maxUncompressedBytes = properties.getMaxUncompressedBytes();
        if (maxCompressedBytes <= 0 || maxUncompressedBytes <= 0
                || maxCompressedBytes == Integer.MAX_VALUE
                || maxUncompressedBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Artifact size limits must be between 1 and Integer.MAX_VALUE - 1.");
        }
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    /**
     * Store a completed Docling response and return metadata for the exact gzip
     * bytes persisted in object storage.
     */
    public IngestionStoredArtifact writeParseArtifact(
            IngestionTaskItem item, String jobId, ParseResponse result) {
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

        validateEmbeddedImages(result);
        ParseResponse durableResult = withoutDiagnosticImageUrls(result);
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
                Instant.now(),
                durableResult);

        byte[] compressed = encode(artifact);
        if (putIfAbsent(objectKey, compressed)) {
            return storedArtifact(objectKey, compressed);
        }

        byte[] existingCompressed = readCompressed(objectKey);
        IngestionParseArtifact existing = readParseArtifact(objectKey, existingCompressed);
        validateParseIdentity(item, objectKey, existing);
        if (!Objects.equals(existing.result(), durableResult)) {
            throw immutableConflict("Existing parse artifact has different content.");
        }
        return storedArtifact(objectKey, existingCompressed);
    }

    private void validateEmbeddedImages(ParseResponse result) {
        if (result == null || result.images() == null || result.images().isEmpty()) {
            return;
        }
        Set<String> blockIds = new HashSet<>();
        for (ParseResponse.Image image : result.images()) {
            if (image == null || image.artifactVersion() == null
                    || image.artifactVersion() != ARTIFACT_VERSION
                    || image.blockId() == null || image.blockId().isBlank()
                    || !Set.of("UPLOADED", "SKIPPED", "FAILED")
                            .contains(image.uploadStatus())
                    || !blockIds.add(image.blockId().trim())
                    || (image.contentHash() != null
                            && !SHA256.matcher(image.contentHash()).matches())) {
                throw corrupt("Parse result contains an invalid embedded-image artifact.", null);
            }
            if ("UPLOADED".equalsIgnoreCase(image.uploadStatus())
                    && (image.imageObjectKey() == null
                            || image.imageObjectKey().isBlank()
                            || image.contentHash() == null
                            || !SHA256.matcher(image.contentHash()).matches()
                            || image.mimeType() == null
                            || image.mimeType().isBlank())) {
                throw corrupt("Uploaded embedded image has incomplete object identity.", null);
            }
        }
    }

    private ParseResponse withoutDiagnosticImageUrls(ParseResponse result) {
        if (result == null || result.images() == null) return result;
        List<ParseResponse.Image> images = result.images().stream()
                .map(image -> image == null ? null : new ParseResponse.Image(
                        image.artifactVersion(), image.blockId(), image.imageObjectKey(),
                        image.uploadStatus(), image.pageNo(), image.bboxes(),
                        image.imageWidth(), image.imageHeight(), image.mimeType(),
                        image.contentHash(), image.alt(), image.caption(),
                        image.contextText(), image.ocrText(), null))
                .toList();
        return new ParseResponse(
                result.requestId(), result.parser(), result.format(), result.text(),
                result.fileType(), result.pages(), result.chunks(), images,
                result.warnings());
    }

    /**
     * Load and strictly validate the parse artifact referenced by an item.
     */
    public ParseResponse readParseResult(IngestionTaskItem item) {
        requireItemIdentity(item);
        IngestionArtifactReference reference = item.getParseResultArtifact();
        String objectKey = resolveObjectKey(
                item.getParseResultObjectKey(), reference, "parseResultObjectKey");
        byte[] compressed = readCompressed(objectKey);
        validateRegistryReference(
                reference,
                PARSE_REGISTRY_TYPE,
                objectKey,
                compressed,
                item.getClaimVersion());
        IngestionParseArtifact artifact = readParseArtifact(objectKey, compressed);
        validateParseIdentity(item, objectKey, artifact);
        return artifact.result();
    }

    /**
     * Resolves uploaded embedded-image objects from an existing parse artifact.
     * Cleanup deliberately reuses the normal parse artifact instead of keeping a
     * second image-specific lifecycle registry.
     */
    public List<String> readEmbeddedImageObjectKeys(
            IngestionArtifactReference reference, String expectedAssetId) {
        if (reference == null) {
            return List.of();
        }
        String objectKey = requireText(reference.getObjectKey(), "parseArtifact.objectKey");
        byte[] compressed = readCompressed(objectKey);
        validateRegistryReference(
                reference,
                PARSE_REGISTRY_TYPE,
                objectKey,
                compressed,
                Long.MAX_VALUE);
        IngestionParseArtifact artifact = readParseArtifact(objectKey, compressed);
        if (!Objects.equals(expectedAssetId, artifact.assetId())
                || !Objects.equals(objectKey, parseObjectKey(artifact))) {
            throw identityMismatch(
                    "Parse artifact identity does not match the asset cleanup request.");
        }
        if (artifact.result().images() == null) {
            return List.of();
        }
        return artifact.result().images().stream()
                .filter(Objects::nonNull)
                .filter(image -> Objects.equals(ARTIFACT_VERSION, image.artifactVersion()))
                .filter(image -> "UPLOADED".equalsIgnoreCase(image.uploadStatus()))
                .map(ParseResponse.Image::imageObjectKey)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
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

    private byte[] readCompressed(String objectKey) {
        byte[] compressed = storageCall(
                () -> objectStoragePort.readArtifact(objectKey, maxCompressedBytes));
        if (compressed == null || compressed.length == 0) {
            throw corrupt("Ingestion artifact is empty.", null);
        }
        if (compressed.length > maxCompressedBytes) {
            throw tooLarge("Artifact exceeds the configured compressed-size limit.");
        }
        return compressed;
    }

    private <T> T decode(byte[] compressed, Class<T> type) {
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

    private IngestionParseArtifact readParseArtifact(String objectKey, byte[] compressed) {
        IngestionParseArtifact artifact =
                decode(compressed, IngestionParseArtifact.class);
        if (artifact == null || artifact.result() == null || artifact.createdAt() == null) {
            throw corrupt("Parse artifact is missing required fields.", null);
        }
        if (!PARSE_ARTIFACT_TYPE.equals(artifact.artifactType())
                || artifact.version() != ARTIFACT_VERSION) {
            throw corrupt("Unsupported parse artifact type or version.", null);
        }
        return artifact;
    }

    private IngestionStoredArtifact storedArtifact(String objectKey, byte[] compressed) {
        return new IngestionStoredArtifact(objectKey, ARTIFACT_VERSION, sha256(compressed));
    }

    private String resolveObjectKey(
            String itemObjectKey,
            IngestionArtifactReference reference,
            String fieldName) {
        if (reference == null) {
            throw corrupt("Artifact registry reference is required.", null);
        }
        String registeredObjectKey =
                requireText(reference.getObjectKey(), fieldName + ".objectKey");
        if (itemObjectKey != null
                && !itemObjectKey.equals(registeredObjectKey)) {
            throw identityMismatch(
                    "Artifact registry object key does not match the claimed execution.");
        }
        return registeredObjectKey;
    }

    private void validateRegistryReference(
            IngestionArtifactReference reference,
            String expectedType,
            String objectKey,
            byte[] compressed,
            long currentClaimVersion) {
        if (!expectedType.equals(reference.getArtifactType())
                || reference.getArtifactVersion() != ARTIFACT_VERSION
                || !objectKey.equals(reference.getObjectKey())) {
            throw corrupt("Artifact registry metadata is inconsistent.", null);
        }

        String provenance = reference.getProvenance();
        String expectedSha256 = reference.getContentSha256();
        if (PRODUCED_PROVENANCE.equals(provenance)) {
            if (reference.getProducerClaimVersion() == null
                    || reference.getProducerClaimVersion() < 1
                    || reference.getProducerClaimVersion() > currentClaimVersion
                    || expectedSha256 == null
                    || !SHA256.matcher(expectedSha256).matches()) {
                throw corrupt(
                        "Produced artifact registry metadata is incomplete.", null);
            }
        } else {
            throw corrupt("Artifact registry provenance is unsupported.", null);
        }

        if (expectedSha256 != null
                && !expectedSha256.equals(sha256(compressed))) {
            throw corrupt(
                    "Ingestion artifact content does not match its registered SHA-256.",
                    null);
        }
    }

    private String sha256(byte[] compressed) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(compressed));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable.", e);
        }
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
