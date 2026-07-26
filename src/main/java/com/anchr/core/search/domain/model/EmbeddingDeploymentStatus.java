package com.anchr.core.search.domain.model;

/**
 * Durable lifecycle of the single embedding/index deployment slot.
 */
public enum EmbeddingDeploymentStatus {
    ACTIVE,
    DESIRED,
    PREPARED,
    BACKFILLING,
    VALIDATING,
    CUTTING_OVER,
    FAILED
}
