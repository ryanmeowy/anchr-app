package com.anchr.core.conversation.application.agent;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import java.time.Duration;

import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.ENABLED;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.FALLBACK_TO_TRADITIONAL;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.MAX_STEPS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.MAX_TOOL_CALLS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.MODEL_TIMEOUT_SECONDS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.NATIVE_TOOL_CHOICE;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.RUNTIME_SNAPSHOT_TTL_SECONDS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.SUMMARY_BATCH_CHARS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.SUMMARY_MAX_CHARS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.SUMMARY_MAX_DOCUMENTS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.SUMMARY_MAX_SEGMENTS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.TASK_MAX_RETRIES;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.TASK_MODEL_TIMEOUT_SECONDS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.TASK_TIMEOUT_SECONDS;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.TOOL_CALL_MODE;
import static com.anchr.core.settings.domain.model.AgentRuntimeConfigKey.TOTAL_TIMEOUT_SECONDS;

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
                unit.getBoolean(RuntimeConfigType.AGENT, ENABLED, true),
                unit.getEnum(
                        RuntimeConfigType.AGENT, TOOL_CALL_MODE,
                        ToolCallMode.class, ToolCallMode.AUTO),
                unit.getEnum(
                        RuntimeConfigType.AGENT, NATIVE_TOOL_CHOICE,
                        NativeToolChoice.class, NativeToolChoice.REQUIRED),
                unit.getBoolean(RuntimeConfigType.AGENT, FALLBACK_TO_TRADITIONAL, true),
                unit.getInt(RuntimeConfigType.AGENT, MAX_STEPS, 12),
                unit.getInt(RuntimeConfigType.AGENT, MAX_TOOL_CALLS, 8),
                unit.getDurationSeconds(
                        RuntimeConfigType.AGENT, TOTAL_TIMEOUT_SECONDS,
                        Duration.ofSeconds(90)),
                unit.getDurationSeconds(
                        RuntimeConfigType.AGENT, MODEL_TIMEOUT_SECONDS,
                        Duration.ofSeconds(30)),
                unit.getDurationSeconds(
                        RuntimeConfigType.AGENT, TASK_TIMEOUT_SECONDS,
                        Duration.ofMinutes(10)),
                unit.getDurationSeconds(
                        RuntimeConfigType.AGENT, TASK_MODEL_TIMEOUT_SECONDS,
                        Duration.ofSeconds(90)),
                unit.getInt(RuntimeConfigType.AGENT, TASK_MAX_RETRIES, 2),
                unit.getDurationSeconds(
                        RuntimeConfigType.AGENT, RUNTIME_SNAPSHOT_TTL_SECONDS,
                        Duration.ofMinutes(35)),
                unit.getInt(RuntimeConfigType.AGENT, SUMMARY_MAX_DOCUMENTS, 3),
                unit.getInt(RuntimeConfigType.AGENT, SUMMARY_MAX_SEGMENTS, 500),
                unit.getInt(RuntimeConfigType.AGENT, SUMMARY_MAX_CHARS, 500_000),
                unit.getInt(RuntimeConfigType.AGENT, SUMMARY_BATCH_CHARS, 12_000));
    }

    public enum ToolCallMode {
        NATIVE, JSON, AUTO
    }

    public enum NativeToolChoice {
        AUTO, REQUIRED
    }
}
