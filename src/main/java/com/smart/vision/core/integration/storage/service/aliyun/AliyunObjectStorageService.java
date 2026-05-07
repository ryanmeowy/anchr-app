package com.smart.vision.core.integration.storage.service.aliyun;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.smart.vision.core.integration.storage.service.aliyun.config.AliyunObjectStorageConfig;
import com.smart.vision.core.search.domain.port.SearchObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.net.URL;
import java.util.Date;

import static com.smart.vision.core.integration.storage.domain.model.PresignedValidityEnum.LONG_TERM_VALIDITY;
import static com.smart.vision.core.integration.storage.domain.model.PresignedValidityEnum.MEDIUM_TERM_VALIDITY;
import static com.smart.vision.core.integration.storage.domain.model.PresignedValidityEnum.SHORT_TERM_VALIDITY;

/**
 * Aliyun OSS implementation of object storage capability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "object-storage", havingValue = "aliyun")
public class AliyunObjectStorageService
        implements SearchObjectStoragePort, IngestionObjectStoragePort {

    private final OSS ossClient;
    private final AliyunObjectStorageConfig aliyunObjectStorageConfig;

    @Override
    public String uploadFile(MultipartFile file) {
        String fileName = "temp/" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
        try {
            ossClient.putObject(aliyunObjectStorageConfig.getBucketName(), fileName, file.getInputStream());
            return fileName;
        } catch (Exception e) {
            log.error("Failed to upload file to OSS: {}", e.getMessage(), e);
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to upload file to object storage.", e);
        }
    }

    @Override
    public String buildAiImageInput(String objectKey, AiInputValidity validity) {
        Long effectiveValidity = validity == AiInputValidity.SHORT
                ? SHORT_TERM_VALIDITY.getValidity()
                : MEDIUM_TERM_VALIDITY.getValidity();
        return buildAiPresignedUrl(objectKey, effectiveValidity);
    }

    @Override
    public String buildDisplayImageUrl(String objectKey) {
        return buildPresignedUrl(objectKey, LONG_TERM_VALIDITY.getValidity());
    }

    @Override
    public String buildDownloadUrl(String objectKey) {
        return buildPresignedUrl(objectKey, SHORT_TERM_VALIDITY.getValidity());
    }

    @Override
    public String buildAiImageInput(String objectKey) {
        return buildAiPresignedUrl(objectKey, SHORT_TERM_VALIDITY.getValidity());
    }

    private String buildPresignedUrl(String objectKey, Long validityTimeMs) {
        Date expiration = new Date(System.currentTimeMillis() + validityTimeMs);
        URL url = ossClient.generatePresignedUrl(aliyunObjectStorageConfig.getBucketName(), objectKey, expiration);
        return url.toString();
    }

    private String buildAiPresignedUrl(String objectKey, Long validityTimeMs) {
        Date expiration = new Date(System.currentTimeMillis() + validityTimeMs);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                aliyunObjectStorageConfig.getBucketName(),
                objectKey,
                HttpMethod.GET
        );
        request.setExpiration(expiration);
        String processParam = aliyunObjectStorageConfig.getImageProcessEmbedding();
        if (StringUtils.hasText(processParam)) {
            request.addQueryParameter("x-oss-process", processParam);
        }
        URL url = ossClient.generatePresignedUrl(request);
        return url.toString();
    }
}
