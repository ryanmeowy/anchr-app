package com.anchr.core.search.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable segment identities scoped by asset and index generation.
 */
public final class SegmentIdentity {

    private SegmentIdentity() {
    }

    public static String chunk(
            String assetId,
            long indexGeneration,
            String rawChunkId,
            int pageNo,
            int chunkOrder,
            String text
    ) {
        String chunkIdentity;
        if (hasText(rawChunkId)) {
            chunkIdentity = "chunk-id\n" + rawChunkId.trim();
        } else {
            chunkIdentity = "fallback\n"
                    + pageNo + "\n"
                    + chunkOrder + "\n"
                    + normalizeText(text);
        }
        return hash(assetId, indexGeneration, chunkIdentity);
    }

    public static String imageVisual(String assetId, long indexGeneration) {
        return hash(assetId, indexGeneration, "asset-projection\nIMAGE_VISUAL");
    }

    public static String documentImage(
            String assetId, long indexGeneration, String blockId) {
        if (!hasText(blockId)) {
            throw new IllegalArgumentException("blockId cannot be blank.");
        }
        return hash(assetId, indexGeneration,
                "document-image\n" + blockId.trim());
    }

    private static String hash(
            String assetId,
            long indexGeneration,
            String projectionIdentity
    ) {
        if (!hasText(assetId)) {
            throw new IllegalArgumentException("assetId cannot be blank.");
        }
        if (indexGeneration < 0L) {
            throw new IllegalArgumentException("indexGeneration cannot be negative.");
        }
        String canonical = assetId.trim()
                + "\n" + indexGeneration
                + "\n" + projectionIdentity;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static String normalizeText(String text) {
        return hasText(text)
                ? text.trim().replaceAll("\\s+", " ")
                : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
