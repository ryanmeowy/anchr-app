package com.anchr.core.ingestion.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import java.time.Duration;

public record IngestionRuntimeSettings(
        int claimBatchSize,
        Duration parsePollInterval,
        Duration parseStageTimeout,
        int stageMaxRetries,
        long embeddingMinIntervalMs,
        int embeddingRateLimitMaxAttempts,
        long embeddingRateLimitBackoffMs,
        boolean embeddedImageUploadEnabled,
        int doclingMaxResponseBytes
) {
    public static IngestionRuntimeSettings load(RuntimeConfigUnit unit) {
        long responseMiB = unit.getLong(
                "INGESTION", "doclingMaxResponseMiB", 256L);
        return new IngestionRuntimeSettings(
                unit.getInt("INGESTION", "claimBatchSize", 32),
                unit.getDurationSeconds(
                        "INGESTION", "parsePollIntervalSeconds",
                        Duration.ofSeconds(2)),
                unit.getDurationMinutes(
                        "INGESTION", "parseStageTimeoutMinutes",
                        Duration.ofMinutes(45)),
                unit.getInt("INGESTION", "stageMaxRetries", 5),
                unit.getLong("INGESTION", "embeddingMinIntervalMs", 1500L),
                unit.getInt(
                        "INGESTION", "embeddingRateLimitMaxAttempts", 5),
                unit.getLong(
                        "INGESTION", "embeddingRateLimitBackoffMs", 5000L),
                unit.getBoolean(
                        "INGESTION", "embeddedImageUploadEnabled", false),
                Math.toIntExact(responseMiB * 1024L * 1024L));
    }
}
