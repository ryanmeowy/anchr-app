package com.anchr.core.integration.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
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
        return buildPresignedUrl(objectKey, duration);
    }

    @Override
    public String buildDisplayImageUrl(String objectKey) {
        return buildPresignedUrl(objectKey, MEDIUM_VALIDITY_MS);
    }

    @Override
    public String buildPreviewUrl(String objectKey) {
        return buildPresignedUrl(objectKey, SHORT_VALIDITY_MS);
    }

    // ── IngestionObjectStoragePort ───────────────────────────────────────

    @Override
    public String buildDownloadUrl(String objectKey) {
        return buildPresignedUrl(objectKey, LONG_VALIDITY_MS);
    }

    @Override
    public String buildAiImageInput(String objectKey) {
        return buildPresignedUrl(objectKey, MEDIUM_VALIDITY_MS);
    }

    // ── internal ─────────────────────────────────────────────────────────

    private OSS buildClient(StorageConfig config) {
        return new OSSClientBuilder().build(config.getEndpoint(),
                decrypt(config.getAccessKeyEnc()), decrypt(config.getSecretKeyEnc()));
    }

    private String buildPresignedUrl(String objectKey, long durationMs) {
        StorageConfig config = loadConfig();
        OSS client = buildClient(config);
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    config.getBucket(), objectKey, HttpMethod.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + durationMs));
            URL url = client.generatePresignedUrl(request);
            return url.toString();
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
