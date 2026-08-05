package com.anchr.core.settings.domain.model;

public enum RebuildRuntimeConfigKey implements RuntimeConfigKey {
    SOURCE_BATCH_SIZE("rebuildSourceBatchSize"),
    EMBEDDING_BATCH_SIZE("rebuildEmbeddingBatchSize"),
    EMBEDDING_CONCURRENCY("rebuildEmbeddingConcurrency"),
    EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS("rebuildEmbeddingRateLimitMaxAttempts"),
    EMBEDDING_RATE_LIMIT_BACKOFF_MS("rebuildEmbeddingRateLimitBackoffMs"),
    DIRTY_ASSET_LIMIT("rebuildDirtyAssetLimit");

    private final String propertyName;

    RebuildRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.REBUILD;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
