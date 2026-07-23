package com.anchr.core.ingestion.application.impl;

import com.anchr.core.kb.domain.model.Asset;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable identity for one business parse attempt. */
final class IngestionParseIdentity {

    static final int INITIAL_ATTEMPT = 1;

    private IngestionParseIdentity() {
    }

    static String requestId(String taskId, String itemId, int parseAttempt) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(itemId) || parseAttempt < 1) {
            throw new IllegalArgumentException("taskId, itemId and a positive parseAttempt are required");
        }
        return taskId.trim() + ":" + itemId.trim() + ":" + parseAttempt;
    }

    static String sourceRevision(Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("asset is required");
        }
        String stableSource;
        if (StringUtils.hasText(asset.getFileHash())) {
            stableSource = "file-hash\0" + asset.getFileHash().trim().toLowerCase(Locale.ROOT);
        } else if (StringUtils.hasText(asset.getObjectKey())) {
            stableSource = "object-key\0" + asset.getObjectKey().trim();
        } else if (StringUtils.hasText(asset.getSourceUrl())) {
            stableSource = "source-url\0" + asset.getSourceUrl().trim();
        } else if (StringUtils.hasText(asset.getId())) {
            stableSource = "asset-id\0" + asset.getId().trim();
        } else {
            throw new IllegalArgumentException("asset has no stable source identity");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stableSource.getBytes(StandardCharsets.UTF_8));
            return "v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
