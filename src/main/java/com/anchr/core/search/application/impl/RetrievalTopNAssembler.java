package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalFacet;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Top-N result projections derived from the final aggregated Retrieval hits.
 */
final class RetrievalTopNAssembler {

    Map<String, List<RetrievalFacet>> buildWindowFacets(List<RetrievalHit> items) {
        if (items == null || items.isEmpty()) {
            return Map.of("assetTypes", List.of(), "hitTypes", List.of());
        }
        return Map.of(
                "assetTypes", toFacet(items.stream().map(RetrievalHit::assetType).toList()),
                "hitTypes", toFacet(items.stream().map(RetrievalHit::segmentType).toList())
        );
    }

    RetrievalInsight buildInsight(
            List<RetrievalHit> items,
            int textHits,
            int vectorHits,
            int fusedCount,
            int rerankCount,
            long latencyMs
    ) {
        List<RetrievalHit> allItems = items == null ? List.of() : items;
        RetrievalInsight.Pipeline pipeline =
                new RetrievalInsight.Pipeline(textHits, vectorHits, fusedCount, rerankCount);

        int high = 0;
        int medium = 0;
        int low = 0;
        for (RetrievalHit item : allItems) {
            Double score = item.score();
            if (score == null) {
                low++;
            } else if (score >= 0.8) {
                high++;
            } else if (score >= 0.5) {
                medium++;
            } else {
                low++;
            }
        }
        RetrievalInsight.RelevanceDistribution relevance =
                new RetrievalInsight.RelevanceDistribution(high, medium, low);

        int vectorCount = 0;
        int contentCount = 0;
        int ocrCount = 0;
        int tagCount = 0;
        int titleCount = 0;
        for (RetrievalHit item : allItems) {
            RetrievalExplain explain = item.explain();
            if (explain == null || explain.hitSources() == null) {
                continue;
            }
            for (String source : explain.hitSources()) {
                if (!StringUtils.hasText(source)) {
                    continue;
                }
                switch (source.toUpperCase(Locale.ROOT)) {
                    case "VECTOR" -> vectorCount++;
                    case "CONTENT" -> contentCount++;
                    case "OCR" -> ocrCount++;
                    case "TAG" -> tagCount++;
                    case "TITLE" -> titleCount++;
                    default -> {
                        // Preserve the existing behavior for unknown sources such as CAPTION.
                    }
                }
            }
        }
        RetrievalInsight.HitSourceDistribution hitSources =
                new RetrievalInsight.HitSourceDistribution(
                        vectorCount, contentCount, ocrCount, tagCount, titleCount);
        return new RetrievalInsight(
                pipeline, relevance, new RetrievalInsight.Risk(low), hitSources, latencyMs);
    }

    private List<RetrievalFacet> toFacet(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.trim();
            counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1L);
        }
        return counts.entrySet().stream()
                .map(entry -> new RetrievalFacet(entry.getKey(), entry.getValue()))
                .toList();
    }
}
