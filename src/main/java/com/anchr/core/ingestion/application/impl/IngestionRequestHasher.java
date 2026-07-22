package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.IngestionApplicationService.IngestionCreateItemCommand;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Produces the stable fingerprint for one ingestion-create request.
 *
 * <p>The item order is intentionally retained. Creation processes items in order and an earlier
 * item can affect the dedupe decision of a later item in the same batch, so reordering is not
 * semantically neutral.</p>
 */
final class IngestionRequestHasher {

    private static final String HASH_SCHEMA = "anchr-ingestion-create-v1";
    private static final String HASH_VERSION_PREFIX = "v1:";

    private IngestionRequestHasher() {
    }

    static String hash(String kbId,
                       IngestionSourceType sourceType,
                       DedupeStrategy dedupeStrategy,
                       List<IngestionCreateItemCommand> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, HASH_SCHEMA);
            updateField(digest, kbId);
            updateField(digest, sourceType.name());
            updateField(digest, dedupeStrategy.name());
            updateInt(digest, items.size());
            for (IngestionCreateItemCommand item : items) {
                byte[] canonicalItem = canonicalItem(item);
                updateInt(digest, canonicalItem.length);
                digest.update(canonicalItem);
            }
            return HASH_VERSION_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", e);
        }
    }

    private static byte[] canonicalItem(IngestionCreateItemCommand item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, item.fileName());
                writeField(output, item.title());
                writeField(output, item.fileType());
                writeField(output, item.mimeType());
                writeField(output, item.sizeBytes() == null ? null : item.sizeBytes().toString());
                writeField(output, item.objectKey());
                writeField(output, item.fileHash());
                writeField(output, item.sourceUrl());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to canonicalize ingestion request.", e);
        }
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes == null ? -1 : bytes.length);
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void writeField(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes == null ? -1 : bytes.length);
        if (bytes != null) {
            output.write(bytes);
        }
    }
}
