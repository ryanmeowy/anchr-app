package com.anchr.core.integration.storage.service.volcengine;

import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "object-storage", havingValue = "volcengine")
public class VolcengineObjectStorageService
        implements SearchObjectStoragePort, IngestionObjectStoragePort {
    @Override
    public String uploadFile(MultipartFile file) {
        throw new UnsupportedOperationException("Unrealized");
    }

    @Override
    public String buildAiImageInput(String objectKey, AiInputValidity validity) {
        throw new UnsupportedOperationException("Unrealized");
    }

    @Override
    public String buildDisplayImageUrl(String objectKey) {
        throw new UnsupportedOperationException("Unrealized");
    }

    @Override
    public String buildPreviewUrl(String objectKey) {
        throw new UnsupportedOperationException("Unrealized");
    }

    @Override
    public String buildDownloadUrl(String objectKey) {
        throw new UnsupportedOperationException("Unrealized");
    }

    @Override
    public String buildAiImageInput(String objectKey) {
        throw new UnsupportedOperationException("Unrealized");
    }
}
