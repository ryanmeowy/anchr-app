package com.anchr.core.conversation.application.agent;

import com.anchr.core.common.util.RuntimeConfigUnit;
import java.time.Duration;

public record AgentRuntimeSettings(
        boolean enabled,
        ToolCallMode toolCallMode,
        NativeToolChoice nativeToolChoice,
        boolean fallbackToTraditional,
        int maxSteps,
        int maxToolCalls,
        Duration totalTimeout,
        Duration modelTimeout,
        Duration taskTimeout,
        Duration taskModelTimeout,
        int taskMaxRetries,
        Duration runtimeSnapshotTtl,
        int summaryMaxDocuments,
        int summaryMaxSegments,
        int summaryMaxChars,
        int summaryBatchChars
) {
    public static AgentRuntimeSettings load(RuntimeConfigUnit unit) {
        return new AgentRuntimeSettings(
                unit.getBoolean("AGENT", "enabled", true),
                unit.getEnum(
                        "AGENT", "toolCallMode",
                        ToolCallMode.class, ToolCallMode.AUTO),
                unit.getEnum(
                        "AGENT", "nativeToolChoice",
                        NativeToolChoice.class, NativeToolChoice.REQUIRED),
                unit.getBoolean("AGENT", "fallbackToTraditional", true),
                unit.getInt("AGENT", "maxSteps", 12),
                unit.getInt("AGENT", "maxToolCalls", 8),
                unit.getDurationSeconds(
                        "AGENT", "totalTimeoutSeconds", Duration.ofSeconds(90)),
                unit.getDurationSeconds(
                        "AGENT", "modelTimeoutSeconds", Duration.ofSeconds(30)),
                unit.getDurationSeconds(
                        "AGENT", "taskTimeoutSeconds", Duration.ofMinutes(10)),
                unit.getDurationSeconds(
                        "AGENT", "taskModelTimeoutSeconds", Duration.ofSeconds(90)),
                unit.getInt("AGENT", "taskMaxRetries", 2),
                unit.getDurationSeconds(
                        "AGENT", "runtimeSnapshotTtlSeconds",
                        Duration.ofMinutes(35)),
                unit.getInt("AGENT", "summaryMaxDocuments", 3),
                unit.getInt("AGENT", "summaryMaxSegments", 500),
                unit.getInt("AGENT", "summaryMaxChars", 500_000),
                unit.getInt("AGENT", "summaryBatchChars", 12_000));
    }

    public enum ToolCallMode {
        NATIVE, JSON, AUTO
    }

    public enum NativeToolChoice {
        AUTO, REQUIRED
    }
}
