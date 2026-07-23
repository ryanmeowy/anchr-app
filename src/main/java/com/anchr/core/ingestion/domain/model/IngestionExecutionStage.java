package com.anchr.core.ingestion.domain.model;

/**
 * Durable execution stage used by the database-backed ingestion scheduler.
 *
 * <p>This state is intentionally separate from {@link IngestionStage}, which is
 * the coarse-grained progress projection exposed to clients.</p>
 */
public enum IngestionExecutionStage {
    PARSE_SUBMIT,
    PARSE_WAIT,
    PARSE_PERSIST,
    EMBED,
    INDEX,
    COMPLETE,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }
}
