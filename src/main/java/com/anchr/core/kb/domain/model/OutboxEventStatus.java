package com.anchr.core.kb.domain.model;

/**
 * Processing state of a durable outbox event.
 */
public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
