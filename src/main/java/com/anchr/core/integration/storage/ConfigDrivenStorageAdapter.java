package com.anchr.core.integration.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
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

import java.net.URL;
import java.util.Date;
import java.util.List;

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

}
