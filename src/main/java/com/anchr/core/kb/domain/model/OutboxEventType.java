package com.anchr.core.kb.domain.model;

/**
 * Event types currently persisted in the transactional outbox.
 */
public enum OutboxEventType {
    DELETE_ASSET,
    UNKNOWN;

    public static OutboxEventType fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
