package com.anchr.core.conversation.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import java.time.Duration;

import static com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey.INTENT_CONTEXT_TURN_LIMIT;
import static com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey.INTENT_ROUTING_ENABLED;
import static com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey.INTENT_TIMEOUT_SECONDS;
import static com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey.LEGACY_EVIDENCE_FALLBACK_ENABLED;

public record ConversationRuntimeSettings(
        boolean intentRoutingEnabled,
        int intentContextTurnLimit,
        Duration intentTimeout,
        boolean legacyEvidenceFallbackEnabled
) {
    public static ConversationRuntimeSettings load(RuntimeConfigUnit unit) {
        return new ConversationRuntimeSettings(
                unit.getBoolean(
                        RuntimeConfigType.CONVERSATION, INTENT_ROUTING_ENABLED, false),
                unit.getInt(
                        RuntimeConfigType.CONVERSATION, INTENT_CONTEXT_TURN_LIMIT, 5),
                unit.getDurationSeconds(
                        RuntimeConfigType.CONVERSATION, INTENT_TIMEOUT_SECONDS,
                        Duration.ofSeconds(5)),
                unit.getBoolean(
                        RuntimeConfigType.CONVERSATION,
                        LEGACY_EVIDENCE_FALLBACK_ENABLED, false));
    }
}
