package com.anchr.core.search.application.api.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record SearchAnswerResult(
        String answer,
        List<Citation> citations,
        List<RetrievalHit> results,
        AnswerTrace answerTrace
) {
    public SearchAnswerResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record Citation(
            Integer citationIndex,
            String assetId,
            String kbId,
            String fileName,
            List<CitationChunk> chunks
    ) {
        public Citation {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    public record CitationChunk(
            String segmentId,
            Integer pageNo,
            Integer chunkOrder,
            String title,
            String content,
            String snippet,
            RetrievalAnchor anchor,
            CitationWhy why
    ) {
    }

    public record CitationWhy(
            Double score,
            List<String> hitSources,
            MatchedBy matchedBy,
            String matchSummary,
            String reason
    ) {
        public CitationWhy {
            hitSources = hitSources == null ? List.of() : List.copyOf(hitSources);
        }

        public record MatchedBy(boolean vector, boolean title, boolean content, boolean ocr) {
        }

        public CitationWhy withReason(String generatedReason) {
            return new CitationWhy(score, hitSources, matchedBy, matchSummary, generatedReason);
        }

        public static String buildSummary(Double score, List<String> hitSources, MatchedBy matchedBy) {
            List<String> parts = new ArrayList<>();
            boolean hasVector = containsIgnoreCase(hitSources, "VECTOR");
            boolean hasContent = containsIgnoreCase(hitSources, "CONTENT");
            boolean hasOcr = containsIgnoreCase(hitSources, "OCR");
            boolean hasTag = containsIgnoreCase(hitSources, "TAG");
            if (hasVector) parts.add("语义匹配");
            List<String> keywordFields = new ArrayList<>();
            if (matchedBy != null) {
                if (matchedBy.content()) keywordFields.add("内容");
                if (matchedBy.title()) keywordFields.add("标题");
                if (matchedBy.ocr()) keywordFields.add("OCR");
            } else {
                if (hasContent) keywordFields.add("内容");
                if (hasOcr) keywordFields.add("OCR");
            }
            if (!keywordFields.isEmpty()) parts.add(String.join("+", keywordFields) + "关键词命中");
            if (hasTag && (matchedBy != null || keywordFields.isEmpty())) parts.add("标签匹配");
            if (hasOcr && matchedBy == null && keywordFields.isEmpty()) parts.add("OCR文本匹配");
            if (parts.isEmpty()) parts.add("无命中信号");
            StringBuilder result = new StringBuilder(String.join(" + ", parts));
            if (score != null) result.append(String.format(Locale.ROOT, " (score: %.2f)", score));
            return result.toString();
        }

        private static boolean containsIgnoreCase(List<String> values, String expected) {
            return values != null && values.stream().anyMatch(expected::equalsIgnoreCase);
        }
    }

    public record AnswerTrace(String mode, boolean grounded, String fallbackReason) {
    }
}
