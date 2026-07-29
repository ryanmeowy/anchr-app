package com.anchr.core.search.application.api.model;

public record RetrievalGenerationWriteReceipt(
        String kbId,
        String assetId,
        long generation,
        int writtenCount,
        String indexName,
        String profileFingerprint
) {
}
