package com.anchr.core.search.application.api.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RetrievalPageResult(
        List<RetrievalHit> items,
        long total,
        Map<String, List<RetrievalFacet>> facets,
        RetrievalInsight insight
) {
    public RetrievalPageResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (facets == null || facets.isEmpty()) {
            facets = Map.of();
        } else {
            Map<String, List<RetrievalFacet>> copy = new LinkedHashMap<>();
            facets.forEach((key, value) -> copy.put(
                    key, value == null ? List.of() : List.copyOf(value)));
            facets = Map.copyOf(copy);
        }
    }
}
