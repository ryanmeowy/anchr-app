package com.anchr.core.settings.domain.model;

import java.util.Arrays;
import java.util.List;

public sealed interface RuntimeConfigKey permits
        SearchRuntimeConfigKey,
        RebuildRuntimeConfigKey,
        ConversationRuntimeConfigKey,
        AgentRuntimeConfigKey,
        IngestionRuntimeConfigKey,
        OutboxRuntimeConfigKey {

    RuntimeConfigType type();

    String propertyName();

    default void requireType(RuntimeConfigType expectedType) {
        if (type() != expectedType) {
            throw new IllegalArgumentException(
                    "runtime config key " + propertyName()
                            + " does not belong to " + expectedType);
        }
    }

    static RuntimeConfigKey parse(RuntimeConfigType type, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime config key is required");
        }
        String normalized = value.trim();
        return keys(type).stream()
                .filter(key -> key.propertyName().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported runtime config key for " + type + ": " + value));
    }

    static List<RuntimeConfigKey> keys(RuntimeConfigType type) {
        RuntimeConfigKey[] keys = switch (type) {
            case SEARCH -> SearchRuntimeConfigKey.values();
            case REBUILD -> RebuildRuntimeConfigKey.values();
            case CONVERSATION -> ConversationRuntimeConfigKey.values();
            case AGENT -> AgentRuntimeConfigKey.values();
            case INGESTION -> IngestionRuntimeConfigKey.values();
            case OUTBOX -> OutboxRuntimeConfigKey.values();
        };
        return Arrays.asList(keys);
    }
}
