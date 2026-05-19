package com.smart.vision.core.ingestion.infrastructure.parser;

import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.model.TextAssetType;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared parser helpers.
 */
final class TextParserSupport {

    private TextParserSupport() {
    }

    static boolean matchesExtension(TextAssetMetadata metadata, String... extensions) {
        if (metadata == null || !StringUtils.hasText(metadata.getFileName())) {
            return false;
        }
        String ext = TextAssetType.resolveExtension(metadata.getFileName());
        for (String candidate : extensions) {
            if (candidate.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesMimeType(TextAssetMetadata metadata, String... mimeTypes) {
        if (metadata == null || !StringUtils.hasText(metadata.getMimeType())) {
            return false;
        }
        String mimeType = metadata.getMimeType().trim().toLowerCase();
        for (String candidate : mimeTypes) {
            if (candidate.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeLineEnding(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    static String decodeTextBytes(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }

        EncodingDecision decision = resolveEncoding(content);
        return new String(content, decision.offset(), content.length - decision.offset(), decision.charset());
    }

    static List<String> splitParagraphs(String content) {
        String normalized = normalizeLineEnding(content);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n+")) {
            String cleaned = paragraph.trim();
            if (StringUtils.hasText(cleaned)) {
                paragraphs.add(cleaned);
            }
        }
        return paragraphs;
    }

    private static EncodingDecision resolveEncoding(byte[] content) {
        if (hasPrefix(content, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new EncodingDecision(StandardCharsets.UTF_8, 3);
        }
        if (hasPrefix(content, (byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x00)) {
            return new EncodingDecision(Charset.forName("UTF-32LE"), 4);
        }
        if (hasPrefix(content, (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF)) {
            return new EncodingDecision(Charset.forName("UTF-32BE"), 4);
        }
        if (hasPrefix(content, (byte) 0xFF, (byte) 0xFE)) {
            return new EncodingDecision(StandardCharsets.UTF_16LE, 2);
        }
        if (hasPrefix(content, (byte) 0xFE, (byte) 0xFF)) {
            return new EncodingDecision(StandardCharsets.UTF_16BE, 2);
        }

        int sampleSize = Math.min(content.length, 512);
        int evenZeroCount = 0;
        int oddZeroCount = 0;
        int evenCount = 0;
        int oddCount = 0;
        for (int i = 0; i < sampleSize; i++) {
            if ((i & 1) == 0) {
                evenCount++;
                if (content[i] == 0) {
                    evenZeroCount++;
                }
            } else {
                oddCount++;
                if (content[i] == 0) {
                    oddZeroCount++;
                }
            }
        }
        if (evenCount > 0 && oddCount > 0) {
            double evenZeroRatio = (double) evenZeroCount / (double) evenCount;
            double oddZeroRatio = (double) oddZeroCount / (double) oddCount;
            if (oddZeroRatio >= 0.3 && evenZeroRatio <= 0.05) {
                return new EncodingDecision(StandardCharsets.UTF_16LE, 0);
            }
            if (evenZeroRatio >= 0.3 && oddZeroRatio <= 0.05) {
                return new EncodingDecision(StandardCharsets.UTF_16BE, 0);
            }
        }
        return new EncodingDecision(StandardCharsets.UTF_8, 0);
    }

    private static boolean hasPrefix(byte[] source, byte... prefix) {
        if (source.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record EncodingDecision(Charset charset, int offset) {
    }
}
