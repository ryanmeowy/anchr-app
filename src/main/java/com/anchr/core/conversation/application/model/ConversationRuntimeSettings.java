package com.anchr.core.conversation.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import java.time.Duration;

public record ConversationRuntimeSettings(
        boolean intentRoutingEnabled,
        int intentContextTurnLimit,
        Duration intentTimeout,
        boolean legacyEvidenceFallbackEnabled
) {
    public static ConversationRuntimeSettings load(RuntimeConfigUnit unit) {
        return new ConversationRuntimeSettings(
                unit.getBoolean(
                        "CONVERSATION", "intentRoutingEnabled", false),
                unit.getInt(
                        "CONVERSATION", "intentContextTurnLimit", 5),
                unit.getDurationSeconds(
                        "CONVERSATION", "intentTimeoutSeconds",
                        Duration.ofSeconds(5)),
                unit.getBoolean(
                        "CONVERSATION", "legacyEvidenceFallbackEnabled", false));
    }
}
