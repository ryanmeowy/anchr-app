package com.smart.vision.core.ingestion.infrastructure.parser;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.port.IngestionObjectStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Loads text asset bytes from object storage via temporary download URL.
 */
@Component
@RequiredArgsConstructor
public class TextAssetContentLoader {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private final IngestionObjectStoragePort objectStoragePort;

    public byte[] load(TextAssetMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getObjectKey())) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Text asset object key is missing");
        }

        String downloadUrl = objectStoragePort.buildDownloadUrl(metadata.getObjectKey());
        if (!StringUtils.hasText(downloadUrl)) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to build download url for text asset");
        }

        try {
            URLConnection connection = new URL(downloadUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            try (InputStream inputStream = connection.getInputStream()) {
                return inputStream.readAllBytes();
            }
        } catch (IOException e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to load text asset content", e);
        }
    }
}
