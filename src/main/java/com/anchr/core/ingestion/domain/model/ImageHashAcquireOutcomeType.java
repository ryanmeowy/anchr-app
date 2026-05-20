package com.anchr.core.ingestion.domain.model;

/**
 * Decision type for acquiring hash processing lock.
 */
public enum ImageHashAcquireOutcomeType {
    ALLOW,
    RETRY_FROM_FAILED,
    REJECT_DUPLICATE,
    REJECT_PROCESSING
}

