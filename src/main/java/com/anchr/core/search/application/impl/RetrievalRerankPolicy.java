package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.model.SearchRuntimeSettings;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded rerank window and score fusion. It deliberately preserves the existing RRF fallback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
final class RetrievalRerankPolicy {

    private final SearchRerankPort rerankPort;
    private final MeterRegistry meterRegistry;

    Outcome rerank(String keyword, List<SegmentRerankCandidate> candidates, int limit) {
        return rerank(
                keyword, candidates, limit, SearchRuntimeSettings.defaults());
    }

    Outcome rerank(
            String keyword,
            List<SegmentRerankCandidate> candidates,
            int limit,
            SearchRuntimeSettings runtimeConfig
    ) {
        if (!StringUtils.hasText(keyword) || candidates.isEmpty()) {
            return new Outcome(candidates, false);
        }
        int windowSize = resolveWindowSize(limit, candidates.size(), runtimeConfig);
        if (windowSize <= 0) {
            return new Outcome(candidates, false);
        }

        List<SegmentRerankCandidate> rerankWindow =
                new ArrayList<>(candidates.subList(0, windowSize));
        List<SegmentRerankCandidate> untouchedTail = windowSize >= candidates.size()
                ? List.of()
                : candidates.subList(windowSize, candidates.size());
        List<String> documents = rerankWindow.stream()
                .map(candidate -> buildDocument(candidate, runtimeConfig.maxDocChars()))
                .toList();

        meterRegistry.counter("kb.search.rerank.calls").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<RerankItem> rerankResults;
        try {
            rerankResults = rerankPort.rerank(keyword, documents, rerankWindow.size());
        } catch (RuntimeException exception) {
            meterRegistry.counter("kb.search.rerank.fallback", "reason", "model_error").increment();
            log.warn("kb search rerank failed, retaining RRF order, candidates={}, windowSize={}, message={}",
                    candidates.size(), windowSize, exception.getMessage());
            return new Outcome(candidates, false);
        } finally {
            sample.stop(Timer.builder("kb.search.rerank.latency")
                    .description("KB unified rerank latency")
                    .register(meterRegistry));
        }
        if (rerankResults == null || rerankResults.isEmpty()) {
            meterRegistry.counter("kb.search.rerank.fallback", "reason", "empty_result").increment();
            return new Outcome(candidates, false);
        }

        Map<Integer, Double> rerankScoreByIndex = new HashMap<>();
        for (RerankItem item : rerankResults) {
            if (item == null || item.index() < 0 || item.index() >= rerankWindow.size()) {
                continue;
            }
            rerankScoreByIndex.put(item.index(), normalizeScore(item.score()));
        }
        if (rerankScoreByIndex.isEmpty()) {
            return new Outcome(candidates, false);
        }

        WeightPair weights = resolveFusionWeights(runtimeConfig);
        List<WindowRankItem> sortedWindow = buildAndSortWindow(
                rerankWindow, rerankScoreByIndex, weights.alpha(), weights.beta());
        List<SegmentRerankCandidate> merged = new ArrayList<>(candidates.size());
        sortedWindow.forEach(item -> merged.add(item.candidate()));
        merged.addAll(untouchedTail);
        log.info("kb search rerank applied, candidates={}, windowSize={}, scored={}, limit={}, alpha={}, beta={}",
                candidates.size(), windowSize, rerankScoreByIndex.size(), limit,
                weights.alpha(), weights.beta());
        return new Outcome(merged, true);
    }

    private List<WindowRankItem> buildAndSortWindow(
            List<SegmentRerankCandidate> rerankWindow,
            Map<Integer, Double> rerankScoreByIndex,
            double alpha,
            double beta
    ) {
        double maxScore = rerankWindow.stream()
                .mapToDouble(SegmentRerankCandidate::score)
                .max()
                .orElse(0d);
        List<WindowRankItem> items = new ArrayList<>(rerankWindow.size());
        for (int index = 0; index < rerankWindow.size(); index++) {
            SegmentRerankCandidate candidate = rerankWindow.get(index);
            double retrievalScore = maxScore <= 0d ? 0d : candidate.score() / maxScore;
            double rerankScore = rerankScoreByIndex.getOrDefault(index, 0d);
            double fusedScore = alpha * retrievalScore + beta * rerankScore;
            SegmentRerankCandidate updatedCandidate = new SegmentRerankCandidate(
                    candidate.segmentId(),
                    candidate.segment(),
                    candidate.highlights(),
                    fusedScore,
                    candidate.bestRawScore(),
                    candidate.hitCount(),
                    candidate.vectorHit()
            );
            items.add(new WindowRankItem(
                    index, updatedCandidate, retrievalScore, rerankScore, fusedScore));
        }
        items.sort(Comparator
                .comparingDouble(WindowRankItem::fusedScore).reversed()
                .thenComparing(Comparator.comparingDouble(WindowRankItem::retrievalScore).reversed())
                .thenComparing(Comparator.comparingDouble(WindowRankItem::rerankScore).reversed())
                .thenComparingInt(WindowRankItem::index));
        return items;
    }

    private int resolveWindowSize(
            int limit, int candidateSize, SearchRuntimeSettings runtimeConfig) {
        if (candidateSize <= 0) {
            return 0;
        }
        if (!runtimeConfig.windowEnabled()) {
            return candidateSize;
        }
        int safeLimit = Math.max(1, limit);
        int baseSize = runtimeConfig.windowSize() > 0
                ? runtimeConfig.windowSize()
                : safeLimit * Math.max(1, runtimeConfig.windowFactor());
        int minSize = Math.max(1, runtimeConfig.windowMin());
        int maxSize = Math.max(minSize, runtimeConfig.windowMax());
        int bounded = Math.max(minSize, Math.min(baseSize, maxSize));
        return Math.min(candidateSize, bounded);
    }

    private WeightPair resolveFusionWeights(SearchRuntimeSettings runtimeConfig) {
        double alpha = clamp01(runtimeConfig.fusionAlpha());
        double beta = clamp01(runtimeConfig.fusionBeta());
        double sum = alpha + beta;
        if (sum <= 0d) {
            return new WeightPair(1d, 0d);
        }
        return new WeightPair(alpha / sum, beta / sum);
    }

    private double normalizeScore(double score) {
        if (score <= 0d) {
            return 0d;
        }
        return Math.min(score, 1d);
    }

    private double clamp01(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 1d) {
            return 1d;
        }
        return value;
    }

    private String buildDocument(SegmentRerankCandidate candidate, int configuredMaxDocChars) {
        if (candidate == null || candidate.segment() == null) {
            return "";
        }
        Segment segment = candidate.segment();
        StringBuilder builder = new StringBuilder(256);
        appendField(builder, "segmentType",
                segment.getSegmentType() == null ? null : segment.getSegmentType().name());
        appendField(builder, "title", segment.getTitle());
        appendField(builder, "content", segment.getContentText());
        appendField(builder, "ocr", segment.getOcrText());
        if (segment.getTags() != null && !segment.getTags().isEmpty()) {
            appendField(builder, "tags", String.join(", ", segment.getTags()));
        }
        String merged = builder.toString();
        int maxDocChars = Math.max(64, configuredMaxDocChars);
        return merged.length() <= maxDocChars ? merged : merged.substring(0, maxDocChars);
    }

    private void appendField(StringBuilder builder, String field, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(field).append(": ").append(value);
    }

    record Outcome(List<SegmentRerankCandidate> candidates, boolean applied) {
    }

    private record WeightPair(double alpha, double beta) {
    }

    private record WindowRankItem(
            int index,
            SegmentRerankCandidate candidate,
            double retrievalScore,
            double rerankScore,
            double fusedScore
    ) {
    }
}
