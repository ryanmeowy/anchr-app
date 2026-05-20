package com.anchr.core.integration.multimodal.support;

import com.anchr.core.ingestion.domain.model.OcrBoundingBox;
import com.anchr.core.ingestion.domain.model.OcrParagraph;
import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import com.anchr.core.ingestion.domain.model.OcrWord;
import com.anchr.core.integration.multimodal.port.OcrParagraphEnhancementPort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies B2-04 paragraph cap and text alignment rules.
 */
@Component
@RequiredArgsConstructor
public class OcrStructuredResultPostProcessor {

    private static final int MAX_PARAGRAPHS = 30;
    private static final double TEXT_DRIFT_THRESHOLD = 0.7d;
    private static final String TEXT_DRIFT_METRIC = "smartvision.ingestion.ocr.text_drift";
    private static final String PARAGRAPH_CAPPED_METRIC = "smartvision.ingestion.ocr.paragraph_capped";

    private final OcrParagraphEnhancementPort enhancementPort;
    private final MeterRegistry meterRegistry;

    public OcrStructuredResult process(String imageInput, OcrStructuredResult rawResult) {
        if (rawResult == null) {
            return OcrStructuredResult.builder()
                    .paragraphs(List.of())
                    .build();
        }
        List<OcrParagraph> paragraphs = normalizeParagraphs(rawResult);
        List<OcrParagraph> capped = capParagraphs(paragraphs);
        List<OcrParagraph> enhanced = enhanceParagraphs(imageInput, capped, rawResult.getImageWidth(), rawResult.getImageHeight());
        return OcrStructuredResult.builder()
                .fullText(joinParagraphText(enhanced))
                .imageWidth(rawResult.getImageWidth())
                .imageHeight(rawResult.getImageHeight())
                .paragraphs(enhanced)
                .build();
    }

    private List<OcrParagraph> normalizeParagraphs(OcrStructuredResult rawResult) {
        List<OcrParagraph> paragraphs = rawResult.getParagraphs();
        if (paragraphs != null && !paragraphs.isEmpty()) {
            return paragraphs.stream()
                    .filter(Objects::nonNull)
                    .toList();
        }
        if (!StringUtils.hasText(rawResult.getFullText())) {
            return List.of();
        }
        return List.of(OcrParagraph.builder()
                .index(0)
                .text(rawResult.getFullText())
                .words(List.of())
                .build());
    }

    private List<OcrParagraph> capParagraphs(List<OcrParagraph> paragraphs) {
        if (paragraphs.size() <= MAX_PARAGRAPHS) {
            return reindex(paragraphs);
        }
        meterRegistry.counter(PARAGRAPH_CAPPED_METRIC).increment();
        int groupSize = (int) Math.ceil((double) paragraphs.size() / MAX_PARAGRAPHS);
        List<OcrParagraph> capped = new ArrayList<>();
        for (int start = 0; start < paragraphs.size(); start += groupSize) {
            int end = Math.min(start + groupSize, paragraphs.size());
            capped.add(mergeParagraphs(capped.size(), paragraphs.subList(start, end)));
        }
        return capped;
    }

    private OcrParagraph mergeParagraphs(int index, List<OcrParagraph> paragraphs) {
        List<OcrWord> words = paragraphs.stream()
                .flatMap(paragraph -> safeWords(paragraph).stream())
                .toList();
        List<OcrBoundingBox> boxes = paragraphs.stream()
                .map(OcrParagraph::getBbox)
                .toList();
        return OcrParagraph.builder()
                .index(index)
                .text(joinParagraphText(paragraphs))
                .words(words)
                .bbox(OcrBoundingBox.enclosing(boxes))
                .build();
    }

    private List<OcrParagraph> enhanceParagraphs(String imageInput,
                                                 List<OcrParagraph> paragraphs,
                                                 Integer imageWidth,
                                                 Integer imageHeight) {
        List<OcrParagraph> enhanced = new ArrayList<>();
        for (OcrParagraph paragraph : paragraphs) {
            String text = enhanceText(imageInput, paragraph, imageWidth, imageHeight);
            enhanced.add(OcrParagraph.builder()
                    .index(enhanced.size())
                    .text(text)
                    .words(safeWords(paragraph))
                    .bbox(paragraph.getBbox())
                    .build());
        }
        return enhanced;
    }

    private String enhanceText(String imageInput, OcrParagraph paragraph, Integer imageWidth, Integer imageHeight) {
        String original = paragraph.getText();
        if (!StringUtils.hasText(original)) {
            return original;
        }
        String enhanced = enhancementPort.enhanceParagraph(imageInput, paragraph, imageWidth, imageHeight);
        if (!StringUtils.hasText(enhanced)) {
            return original;
        }
        if (similarity(original, enhanced) < TEXT_DRIFT_THRESHOLD) {
            meterRegistry.counter(TEXT_DRIFT_METRIC).increment();
            return original;
        }
        return enhanced.trim();
    }

    private List<OcrParagraph> reindex(List<OcrParagraph> paragraphs) {
        List<OcrParagraph> reindexed = new ArrayList<>();
        for (OcrParagraph paragraph : paragraphs) {
            reindexed.add(OcrParagraph.builder()
                    .index(reindexed.size())
                    .text(paragraph.getText())
                    .words(safeWords(paragraph))
                    .bbox(paragraph.getBbox())
                    .build());
        }
        return reindexed;
    }

    private List<OcrWord> safeWords(OcrParagraph paragraph) {
        return paragraph.getWords() == null ? List.of() : paragraph.getWords();
    }

    private String joinParagraphText(List<OcrParagraph> paragraphs) {
        return paragraphs.stream()
                .map(OcrParagraph::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse(null);
    }

    private double similarity(String left, String right) {
        String a = left.trim();
        String b = right.trim();
        if (a.equals(b)) {
            return 1d;
        }
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 1d;
        }
        return 1d - ((double) levenshteinDistance(a, b) / maxLength);
    }

    private int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] tmp = previous;
            previous = current;
            current = tmp;
        }
        return previous[right.length()];
    }
}
