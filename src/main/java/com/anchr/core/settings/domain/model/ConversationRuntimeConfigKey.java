package com.anchr.core.settings.domain.model;

public enum ConversationRuntimeConfigKey implements RuntimeConfigKey {
    INTENT_ROUTING_ENABLED("intentRoutingEnabled"),
    INTENT_CONTEXT_TURN_LIMIT("intentContextTurnLimit"),
    INTENT_TIMEOUT_SECONDS("intentTimeoutSeconds"),
    LEGACY_EVIDENCE_FALLBACK_ENABLED("legacyEvidenceFallbackEnabled");

    private final String propertyName;

    ConversationRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.CONVERSATION;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
