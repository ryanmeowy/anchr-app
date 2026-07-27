package com.anchr.core.integration.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Config-driven object storage adapter backed by storage_config.
 */
@Slf4j
@Primary
@Service
public class ConfigDrivenStorageAdapter implements SearchObjectStoragePort, IngestionObjectStoragePort {

    private static final long SHORT_VALIDITY_MS = 60_000L;
    private static final long MEDIUM_VALIDITY_MS = 300_000L;
    private static final long LONG_VALIDITY_MS = 3_600_000L;
    private static final String OSS_FORBID_OVERWRITE = "x-oss-forbid-overwrite";
    private static final String ARTIFACT_SHA256_METADATA = "artifact-sha256";
    private static final String EMBEDDING_IMAGE_PROCESS =
            "image/resize,m_lfit,w_1536,h_1536,limit_1/quality,q_75/format,jpg";

    private final StorageConfigRepository configRepository;
    private final AesUtil aesUtil;

    public ConfigDrivenStorageAdapter(StorageConfigRepository configRepository, AesUtil aesUtil) {
        this.configRepository = configRepository;
        this.aesUtil = aesUtil;
    }

    // ── SearchObjectStoragePort ──────────────────────────────────────────

    @Override
    public String uploadFile(MultipartFile file) {
        StorageConfig config = loadConfig();
        String fileName = (config.getPrefix() != null ? config.getPrefix() : "temp/")
                + System.currentTimeMillis() + "_" + file.getOriginalFilename();
        OSS client = buildClient(config);
        try {
            client.putObject(config.getBucket(), fileName, file.getInputStream());
            return fileName;
        } catch (Exception e) {
            log.error("Failed to upload file to OSS: {}", e.getMessage(), e);
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to upload file to object storage.", e);
        } finally {
            client.shutdown();
        }
    }

    @Override
    public String buildAiImageInput(String objectKey, AiInputValidity validity) {
        long duration = validity == AiInputValidity.SHORT ? SHORT_VALIDITY_MS : MEDIUM_VALIDITY_MS;
        return buildProcessedImageUrl(objectKey, duration);
    }

    @Override
    public String buildDisplayImageUrl(String objectKey) {
        return buildPresignedUrl(objectKey, MEDIUM_VALIDITY_MS);
    }

    @Override
    public SignedObjectUrl buildPreviewUrl(String objectKey) {
        return buildSignedObjectUrl(objectKey, SHORT_VALIDITY_MS);
    }

    // ── IngestionObjectStoragePort ───────────────────────────────────────

    @Override
    public String buildDownloadUrl(String objectKey) {
        return buildPresignedUrl(objectKey, LONG_VALIDITY_MS);
    }

    @Override
    public String buildImageEmbeddingUrl(String objectKey) {
        return buildProcessedImageUrl(objectKey, MEDIUM_VALIDITY_MS);
    }

    @Override
    public boolean putArtifactIfAbsent(String objectKey, byte[] content,
                                       String contentType, String contentEncoding) {
        requireArtifactArguments(objectKey, content);
        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(contentType);
            if (contentEncoding != null && !contentEncoding.isBlank()) {
                metadata.setContentEncoding(contentEncoding);
            }
            metadata.setContentMD5(md5Base64(content));
            metadata.addUserMetadata(ARTIFACT_SHA256_METADATA, sha256Hex(content));
            // OSS evaluates this header atomically on PUT. Do not replace it with
            // doesObjectExist(), which would reintroduce a check-then-put race.
            metadata.setHeader(OSS_FORBID_OVERWRITE, Boolean.TRUE.toString());

            PutObjectRequest request = new PutObjectRequest(
                    config.getBucket(), objectKey, new ByteArrayInputStream(content), metadata);
            client.putObject(request);
            return true;
        } catch (OSSException e) {
            if (isAlreadyExists(e)) {
                return false;
            }
            log.error("Failed to persist immutable ingestion artifact {}: {}",
                    objectKey, e.getMessage());
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Failed to persist ingestion artifact.", e);
        } catch (RuntimeException e) {
            log.error("Failed to persist immutable ingestion artifact {}: {}",
                    objectKey, e.getMessage());
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Failed to persist ingestion artifact.", e);
        } finally {
            client.shutdown();
        }
    }

    @Override
    public byte[] readArtifact(String objectKey, int maxBytes) {
        return readArtifactInternal(objectKey, maxBytes, false).orElseThrow();
    }

    @Override
    public Optional<byte[]> readArtifactIfPresent(String objectKey, int maxBytes) {
        return readArtifactInternal(objectKey, maxBytes, true);
    }

    private Optional<byte[]> readArtifactInternal(
            String objectKey, int maxBytes, boolean missingAllowed) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Artifact object key must not be blank.");
        }
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Artifact read limit must be between 1 and Integer.MAX_VALUE - 1.");
        }

        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try (OSSObject object = client.getObject(config.getBucket(), objectKey)) {
            ObjectMetadata metadata = object.getObjectMetadata();
            if (metadata != null && metadata.getContentLength() > maxBytes) {
                throw new BusinessException(ApiError.INTERNAL_ERROR,
                        "Ingestion artifact exceeds the configured compressed-size limit.");
            }

            byte[] content = object.getObjectContent().readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new BusinessException(ApiError.INTERNAL_ERROR,
                        "Ingestion artifact exceeds the configured compressed-size limit.");
            }
            verifyArtifactDigest(metadata, content);
            return Optional.of(content);
        } catch (BusinessException e) {
            throw e;
        } catch (OSSException e) {
            if (missingAllowed && "NoSuchKey".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            log.error("Failed to read ingestion artifact {}: {}", objectKey, e.getMessage());
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Failed to read ingestion artifact.", e);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to read ingestion artifact {}: {}", objectKey, e.getMessage());
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Failed to read ingestion artifact.", e);
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try {
            client.deleteObject(config.getBucket(), objectKey.trim());
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void deleteObjectsByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException(
                    "Object deletion prefix must be a non-empty directory prefix.");
        }
        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try {
            String marker = null;
            do {
                ListObjectsRequest request = new ListObjectsRequest(config.getBucket());
                request.setPrefix(prefix);
                request.setMarker(marker);
                request.setMaxKeys(1000);
                ObjectListing listing = client.listObjects(request);
                List<String> keys = listing.getObjectSummaries().stream()
                        .map(OSSObjectSummary::getKey)
                        .filter(key -> key != null && key.startsWith(prefix))
                        .toList();
                if (!keys.isEmpty()) {
                    client.deleteObjects(new DeleteObjectsRequest(config.getBucket())
                            .withKeys(keys)
                            .withQuiet(true));
                }
                marker = listing.isTruncated() ? listing.getNextMarker() : null;
            } while (marker != null);
        } finally {
            client.shutdown();
        }
    }

    // ── internal ─────────────────────────────────────────────────────────

    private OSS buildClient(StorageConfig config) {
        return new OSSClientBuilder().build(
                config.getEndpoint(),
                decrypt(config.getAccessKeyEnc()),
                decrypt(config.getSecretKeyEnc()));
    }

    private String buildPresignedUrl(String objectKey, long durationMs) {
        return buildSignedObjectUrl(objectKey, durationMs).url();
    }

    private String buildProcessedImageUrl(String objectKey, long durationMs) {
        return buildSignedObjectUrl(objectKey, durationMs, EMBEDDING_IMAGE_PROCESS).url();
    }

    private SignedObjectUrl buildSignedObjectUrl(String objectKey, long durationMs) {
        return buildSignedObjectUrl(objectKey, durationMs, null);
    }

    private SignedObjectUrl buildSignedObjectUrl(
            String objectKey,
            long durationMs,
            String process
    ) {
        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try {
            long expiresAt = System.currentTimeMillis() + durationMs;
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    config.getBucket(), objectKey, HttpMethod.GET);
            request.setExpiration(new Date(expiresAt));
            if (process != null && !process.isBlank()) {
                request.setProcess(process);
            }
            URL url = client.generatePresignedUrl(request);
            return new SignedObjectUrl(url.toString(), expiresAt);
        } catch (Exception e) {
            log.error("Failed to sign URL for {}: {}", objectKey, e.getMessage());
            throw new BusinessException(ApiError.PREVIEW_URL_SIGN_FAILED);
        } finally {
            client.shutdown();
        }
    }

    private StorageConfig loadConfig() {
        return configRepository.find()
                .orElseThrow(() -> new IllegalStateException(
                        "Object storage is not configured. Save config via PATCH /api/v1/settings/storage."));
    }

    private String decrypt(String encrypted) {
        try { return aesUtil.decrypt(encrypted); }
        catch (Exception e) { throw new IllegalStateException("Failed to decrypt storage credential.", e); }
    }

    private void requireArtifactArguments(String objectKey, byte[] content) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Artifact object key must not be blank.");
        }
        Objects.requireNonNull(content, "Artifact content must not be null.");
    }

    private boolean isAlreadyExists(OSSException exception) {
        return "FileAlreadyExists".equals(exception.getErrorCode())
                || "ObjectAlreadyExists".equals(exception.getErrorCode())
                || "PreconditionFailed".equals(exception.getErrorCode());
    }

    private String md5Base64(byte[] content) {
        return Base64.getEncoder().encodeToString(digest("MD5", content));
    }

    private String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(digest("SHA-256", content));
    }

    private byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required digest algorithm is unavailable: " + algorithm, e);
        }
    }

    private void verifyArtifactDigest(ObjectMetadata metadata, byte[] content) {
        if (metadata == null) {
            throw new BusinessException(ApiError.INTERNAL_ERROR,
                    "Ingestion artifact is missing integrity metadata.");
        }
        Map<String, String> userMetadata = metadata.getUserMetadata();
        String expected = userMetadata == null ? null : userMetadata.get(ARTIFACT_SHA256_METADATA);
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new BusinessException(ApiError.INTERNAL_ERROR,
                    "Ingestion artifact is missing valid integrity metadata.");
        }
        if (!MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                sha256Hex(content).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ApiError.INTERNAL_ERROR,
                    "Ingestion artifact failed its integrity check.");
        }
    }

}
