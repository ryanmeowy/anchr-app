package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextAssetType;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Loads text asset bytes from object storage via temporary download URL.
 */
@Component
@RequiredArgsConstructor
public class TextAssetContentLoader {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private final IngestionObjectStoragePort objectStoragePort;

    public void enrichRemoteMetadata(TextAssetMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getSourceUrl())) {
            return;
        }
        try {
            URLConnection connection = openUrlConnection(metadata.getSourceUrl());
            connection.connect();
            enrichMimeType(metadata, connection.getContentType());
            enrichFileName(metadata, connection);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to inspect source URL metadata", e);
        }
    }

    public byte[] load(TextAssetMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Text asset metadata is missing");
        }
        if (StringUtils.hasText(metadata.getSourceUrl())) {
            return loadFromUrl(metadata.getSourceUrl());
        }
        if (!StringUtils.hasText(metadata.getObjectKey())) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Text asset object key or source URL is missing");
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

    private byte[] loadFromUrl(String sourceUrl) {
        try {
            URLConnection connection = openUrlConnection(sourceUrl);
            try (InputStream inputStream = connection.getInputStream()) {
                return inputStream.readAllBytes();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to load source URL content", e);
        }
    }

    private URLConnection openUrlConnection(String sourceUrl) throws Exception {
        URI uri = URI.create(sourceUrl.trim());
        validateUri(uri);
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (connection instanceof HttpURLConnection httpConnection) {
            httpConnection.setInstanceFollowRedirects(true);
        }
        return connection;
    }

    private void validateUri(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "Only http and https URL ingestion is supported.");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "URL host cannot be blank.");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new BusinessException(ApiError.INVALID_REQUEST, "Private or local URL ingestion is not allowed.");
            }
        }
    }

    private void enrichMimeType(TextAssetMetadata metadata, String contentType) {
        if (StringUtils.hasText(metadata.getMimeType()) || !StringUtils.hasText(contentType)) {
            return;
        }
        metadata.setMimeType(contentType.trim());
    }

    private void enrichFileName(TextAssetMetadata metadata, URLConnection connection) {
        if (StringUtils.hasText(TextAssetType.resolveExtension(metadata.getFileName()))) {
            return;
        }
        String headerFileName = resolveDispositionFileName(connection.getHeaderField("Content-Disposition"));
        if (StringUtils.hasText(headerFileName)) {
            metadata.setFileName(headerFileName);
            return;
        }
        String urlFileName = resolveUrlFileName(connection.getURL());
        if (StringUtils.hasText(TextAssetType.resolveExtension(urlFileName))) {
            metadata.setFileName(urlFileName);
        }
    }

    private String resolveDispositionFileName(String contentDisposition) {
        if (!StringUtils.hasText(contentDisposition)) {
            return "";
        }
        for (String part : contentDisposition.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(trimmed.substring(eq + 1).trim());
            if ("filename*".equals(key)) {
                int charsetMarker = value.indexOf("''");
                String encoded = charsetMarker >= 0 ? value.substring(charsetMarker + 2) : value;
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }
            if ("filename".equals(key)) {
                return value;
            }
        }
        return "";
    }

    private String resolveUrlFileName(URL url) {
        if (url == null || !StringUtils.hasText(url.getPath())) {
            return "";
        }
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        return URLDecoder.decode(fileName, StandardCharsets.UTF_8);
    }

    private String stripQuotes(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
