package com.smart.vision.core.integration.multimodal.manager.aliyun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.domain.model.OcrBoundingBox;
import com.smart.vision.core.ingestion.domain.model.OcrParagraph;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import com.smart.vision.core.ingestion.domain.model.OcrWord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Aliyun RecognizeAdvanced Data JSON into the bbox protocol.
 */
@Component
@RequiredArgsConstructor
public class AliyunAdvancedOcrResultParser {

    private final ObjectMapper objectMapper;

    public OcrStructuredResult parse(String data) {
        if (!StringUtils.hasText(data)) {
            return emptyResult();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(data));
            List<OcrWord> words = readWords(root.path("prism_wordsInfo"));
            return OcrStructuredResult.builder()
                    .fullText(readText(root, "content"))
                    .imageWidth(readInt(root, "orgWidth", "width"))
                    .imageHeight(readInt(root, "orgHeight", "height"))
                    .paragraphs(readParagraphs(root.path("prism_paragraphsInfo"), words))
                    .build();
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to parse Aliyun OCR response", e);
        }
    }

    private OcrStructuredResult emptyResult() {
        return OcrStructuredResult.builder()
                .paragraphs(List.of())
                .build();
    }

    private String extractJsonObject(String data) {
        String trimmed = data.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Aliyun OCR response data is not JSON");
        }
        return trimmed.substring(start, end + 1);
    }

    private List<OcrWord> readWords(JsonNode wordsNode) {
        if (!wordsNode.isArray()) {
            return List.of();
        }
        List<OcrWord> words = new ArrayList<>();
        for (JsonNode wordNode : wordsNode) {
            String text = readText(wordNode, "word");
            if (!StringUtils.hasText(text)) {
                continue;
            }
            words.add(OcrWord.builder()
                    .paragraphIndex(readInt(wordNode, "paragraphId", "paragraphIndex"))
                    .text(text)
                    .bbox(readPointBox(wordNode.path("pos")))
                    .build());
        }
        return words;
    }

    private List<OcrParagraph> readParagraphs(JsonNode paragraphsNode, List<OcrWord> words) {
        if (!paragraphsNode.isArray()) {
            return readWordFallbackParagraphs(words);
        }
        Map<Integer, List<OcrWord>> wordsByParagraph = groupWordsByParagraph(words);
        List<OcrParagraph> paragraphs = new ArrayList<>();
        int index = 0;
        for (JsonNode paragraphNode : paragraphsNode) {
            String text = readText(paragraphNode, "word", "text", "paragraph");
            if (!StringUtils.hasText(text)) {
                continue;
            }
            Integer paragraphId = readInt(paragraphNode, "paragraphId", "paragraphIndex");
            int paragraphKey = paragraphId == null ? index : paragraphId;
            List<OcrWord> paragraphWords = wordsByParagraph.getOrDefault(paragraphKey, List.of());
            paragraphs.add(OcrParagraph.builder()
                    .index(index)
                    .text(text)
                    .words(paragraphWords)
                    .bbox(enclosingBox(paragraphWords))
                    .build());
            index++;
        }
        return paragraphs;
    }

    private Map<Integer, List<OcrWord>> groupWordsByParagraph(List<OcrWord> words) {
        Map<Integer, List<OcrWord>> grouped = new LinkedHashMap<>();
        if (words == null || words.isEmpty()) {
            return grouped;
        }
        for (OcrWord word : words) {
            if (word.getParagraphIndex() == null) {
                continue;
            }
            grouped.computeIfAbsent(word.getParagraphIndex(), ignored -> new ArrayList<>()).add(word);
        }
        return grouped;
    }

    private List<OcrParagraph> readWordFallbackParagraphs(List<OcrWord> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<OcrParagraph> paragraphs = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            OcrWord word = words.get(i);
            paragraphs.add(OcrParagraph.builder()
                    .index(i)
                    .text(word.getText())
                    .words(List.of(word))
                    .bbox(word.getBbox())
                    .build());
        }
        return paragraphs;
    }

    private OcrBoundingBox enclosingBox(List<OcrWord> words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        return OcrBoundingBox.enclosing(words.stream()
                .map(OcrWord::getBbox)
                .toList());
    }

    private OcrBoundingBox readPointBox(JsonNode pointsNode) {
        if (!pointsNode.isArray() || pointsNode.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (JsonNode point : pointsNode) {
            Integer x = readInt(point, "x");
            Integer y = readInt(point, "y");
            if (x == null || y == null) {
                continue;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (maxX <= minX || maxY <= minY) {
            return null;
        }
        return OcrBoundingBox.pixel(minX, minY, maxX - minX, maxY - minY);
    }

    private String readText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }
}
