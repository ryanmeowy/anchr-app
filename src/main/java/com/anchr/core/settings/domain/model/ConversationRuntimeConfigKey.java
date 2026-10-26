package com.anchr.core.settings.domain.model;

public enum ConversationRuntimeConfigKey implements RuntimeConfigKey {
    INTENT_ROUTING_ENABLED("intentRoutingEnabled"),
    INTENT_CONTEXT_TURN_LIMIT("intentContextTurnLimit"),
    INTENT_TIMEOUT_SECONDS("intentTimeoutSeconds"),
    LEGACY_EVIDENCE_FALLBACK_ENABLED("legacyEvidenceFallbackEnabled"),
    /**
     * Retired: the pre-writing evidence outline was an evaluation-only experiment and its
     * implementation has been removed. The key is kept so existing stored runtime configs still
     * parse, but nothing reads it and toggling it has no effect.
     */
    EVIDENCE_OUTLINE_ENABLED("evidenceOutlineEnabled");

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
