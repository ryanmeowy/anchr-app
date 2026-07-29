package com.anchr.core.search.application.api.model;

import java.util.List;

/** Retrieval request for ranked hits without product-page metadata. */
public record RetrievalHitQuery(
        String query,
        Integer limit,
        List<String> kbIds,
        List<String> assetIds,
        List<String> hitTypes
) {
    public RetrievalHitQuery {
        kbIds = copy(kbIds);
        assetIds = copy(assetIds);
        hitTypes = copy(hitTypes);
    }

    private static <T> List<T> copy(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }
}
