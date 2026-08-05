package com.anchr.core.search.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;

import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.DIRTY_ASSET_LIMIT;
import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.EMBEDDING_BATCH_SIZE;
import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.EMBEDDING_CONCURRENCY;
import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.EMBEDDING_RATE_LIMIT_BACKOFF_MS;
import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS;
import static com.anchr.core.settings.domain.model.RebuildRuntimeConfigKey.SOURCE_BATCH_SIZE;

public record SearchRebuildRuntimeSettings(
        int sourceBatchSize,
        int embeddingBatchSize,
        int embeddingConcurrency,
        int rateLimitMaxAttempts,
        long rateLimitBackoffMs,
        int dirtyAssetLimit
) {
    public static SearchRebuildRuntimeSettings defaults() {
        return new SearchRebuildRuntimeSettings(200, 32, 2, 5, 5_000L, 100_000);
    }

    public static SearchRebuildRuntimeSettings load(RuntimeConfigUnit unit) {
        if (unit == null) {
            return defaults();
        }
        return new SearchRebuildRuntimeSettings(
                positive(unit.getInt(RuntimeConfigType.REBUILD, SOURCE_BATCH_SIZE, 200)),
                positive(unit.getInt(RuntimeConfigType.REBUILD, EMBEDDING_BATCH_SIZE, 32)),
                positive(unit.getInt(RuntimeConfigType.REBUILD, EMBEDDING_CONCURRENCY, 2)),
                positive(unit.getInt(
                        RuntimeConfigType.REBUILD,
                        EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS,
                        5)),
                positive(unit.getLong(
                        RuntimeConfigType.REBUILD,
                        EMBEDDING_RATE_LIMIT_BACKOFF_MS,
                        5_000L)),
                positive(unit.getInt(RuntimeConfigType.REBUILD, DIRTY_ASSET_LIMIT, 100_000)));
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new IllegalStateException("Rebuild runtime config must be positive");
        }
        return value;
    }

    private static long positive(long value) {
        if (value <= 0L) {
            throw new IllegalStateException("Rebuild runtime config must be positive");
        }
        return value;
    }
}
