package com.anchr.core.search.application.api.model;

import java.util.List;

/** Retrieval request for the public bounded Top-N search endpoint. */
public record RetrievalTopNQuery(
        String query,
        List<String> keywords,
        Integer limit,
        List<String> kbIds,
        List<String> assetIds,
        List<String> assetTypes,
        List<String> hitTypes,
        Long createdFrom,
        Long createdTo
) {
    public RetrievalTopNQuery {
        keywords = copy(keywords);
        kbIds = copy(kbIds);
        assetIds = copy(assetIds);
        assetTypes = copy(assetTypes);
        hitTypes = copy(hitTypes);
    }

    private static <T> List<T> copy(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }
}
