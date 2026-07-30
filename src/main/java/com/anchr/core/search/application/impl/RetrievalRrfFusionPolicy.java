package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure RRF fusion and per-asset/type diversification used by the Retrieval query use case.
 */
final class RetrievalRrfFusionPolicy {

    List<SegmentRerankCandidate> fuse(
            List<SegmentHit> textHits,
            List<SegmentHit> textVectorHits,
            List<SegmentHit> imageVectorHits,
            int rankConstant
    ) {
        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        int safeRankConstant = Math.max(1, rankConstant);
        ingest(textHits, false, safeRankConstant, grouped);
        ingest(textVectorHits, true, safeRankConstant, grouped);
        ingest(imageVectorHits, true, safeRankConstant, grouped);

        return grouped.values().stream()
                .sorted(Comparator.comparingDouble(Accumulator::rrfScore).reversed()
                        .thenComparing(Comparator.comparingInt(Accumulator::hitCount).reversed())
                        .thenComparing(Comparator.comparingDouble(Accumulator::bestRawScore).reversed()))
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();
    }

    List<SegmentRerankCandidate> diversify(List<SegmentRerankCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        List<SegmentRerankCandidate> diversified = new ArrayList<>();
        for (SegmentRerankCandidate candidate : candidates) {
            Segment segment = candidate == null ? null : candidate.segment();
            if (segment == null) {
                continue;
            }
            String key = Objects.toString(segment.getAssetId(), "") + "\n"
                    + Objects.toString(segment.getSegmentType(), "");
            int count = counts.getOrDefault(key, 0);
            if (count >= 3) {
                continue;
            }
            counts.put(key, count + 1);
            diversified.add(candidate);
        }
        return List.copyOf(diversified);
    }

    private void ingest(
            List<SegmentHit> ranking,
            boolean vectorRoute,
            int rankConstant,
            Map<String, Accumulator> grouped
    ) {
        if (ranking == null || ranking.isEmpty()) {
            return;
        }
        for (int i = 0; i < ranking.size(); i++) {
            SegmentHit hit = ranking.get(i);
            Segment segment = hit == null ? null : hit.getSegment();
            String segmentId = segment == null ? null : segment.getSegmentId();
            if (!StringUtils.hasText(segmentId)) {
                continue;
            }
            Accumulator accumulator = grouped.computeIfAbsent(segmentId, ignored -> new Accumulator());
            accumulator.rrfScore += reciprocal(rankConstant, i);
            accumulator.hitCount++;
            accumulator.bestRawScore = Math.max(accumulator.bestRawScore, hit.getRawScore());
            if (vectorRoute) {
                accumulator.vectorHit = true;
                if (accumulator.vectorSource == null) {
                    accumulator.vectorSource = hit;
                }
            } else if (accumulator.textSource == null) {
                accumulator.textSource = hit;
            }
        }
    }

    // RRF(document) = sum(1 / (k + rank))
    private double reciprocal(int rankConstant, int rankIndex) {
        return 1d / (rankConstant + rankIndex + 1d);
    }

    private SegmentRerankCandidate toCandidate(Accumulator accumulator) {
        SegmentHit displaySource = accumulator.textSource != null
                ? accumulator.textSource : accumulator.vectorSource;
        Segment segment = displaySource == null ? null : displaySource.getSegment();
        if (segment == null) {
            return null;
        }
        Map<String, String> highlights =
                accumulator.textSource == null || accumulator.textSource.getHighlights() == null
                        ? Map.of()
                        : accumulator.textSource.getHighlights();
        return new SegmentRerankCandidate(
                segment.getSegmentId(),
                segment,
                highlights,
                accumulator.rrfScore,
                accumulator.bestRawScore,
                accumulator.hitCount,
                accumulator.vectorHit
        );
    }

    private static final class Accumulator {
        private double rrfScore;
        private int hitCount;
        private double bestRawScore;
        private boolean vectorHit;
        private SegmentHit vectorSource;
        private SegmentHit textSource;

        private double rrfScore() {
            return rrfScore;
        }

        private int hitCount() {
            return hitCount;
        }

        private double bestRawScore() {
            return bestRawScore;
        }
    }
}
