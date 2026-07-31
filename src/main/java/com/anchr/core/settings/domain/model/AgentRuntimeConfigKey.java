package com.anchr.core.settings.domain.model;

public enum AgentRuntimeConfigKey implements RuntimeConfigKey {
    ENABLED("enabled"),
    TOOL_CALL_MODE("toolCallMode"),
    NATIVE_TOOL_CHOICE("nativeToolChoice"),
    FALLBACK_TO_TRADITIONAL("fallbackToTraditional"),
    MAX_STEPS("maxSteps"),
    MAX_TOOL_CALLS("maxToolCalls"),
    TOTAL_TIMEOUT_SECONDS("totalTimeoutSeconds"),
    MODEL_TIMEOUT_SECONDS("modelTimeoutSeconds"),
    TASK_TIMEOUT_SECONDS("taskTimeoutSeconds"),
    TASK_MODEL_TIMEOUT_SECONDS("taskModelTimeoutSeconds"),
    TASK_MAX_RETRIES("taskMaxRetries"),
    RUNTIME_SNAPSHOT_TTL_SECONDS("runtimeSnapshotTtlSeconds"),
    SUMMARY_MAX_DOCUMENTS("summaryMaxDocuments"),
    SUMMARY_MAX_SEGMENTS("summaryMaxSegments"),
    SUMMARY_MAX_CHARS("summaryMaxChars"),
    SUMMARY_BATCH_CHARS("summaryBatchChars");

    private final String propertyName;

    AgentRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.AGENT;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
