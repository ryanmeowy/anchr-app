package com.anchr.core.search.application.api.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RetrievalTopNResult(
        List<RetrievalHit> items,
        Map<String, List<RetrievalFacet>> windowFacets,
        RetrievalInsight insight
) {
    public RetrievalTopNResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (windowFacets == null || windowFacets.isEmpty()) {
            windowFacets = Map.of();
        } else {
            Map<String, List<RetrievalFacet>> copy = new LinkedHashMap<>();
            windowFacets.forEach((key, value) -> copy.put(
                    key, value == null ? List.of() : List.copyOf(value)));
            windowFacets = Map.copyOf(copy);
        }
    }
}
