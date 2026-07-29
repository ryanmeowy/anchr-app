package com.anchr.core.kb.domain.model;

/**
 * User-facing availability of a document in the knowledge base.
 *
 * <p>An asset with an active index generation remains answerable even when a
 * later maintenance attempt is still running or has failed.</p>
 */
public enum DocumentAvailabilityStatus {
    ANSWERABLE,
    PROCESSING,
    FAILED;

    public static DocumentAvailabilityStatus from(Asset asset) {
        if (asset.getActiveIndexGeneration() > 0
                || asset.getIndexStatus() == DocumentIndexStatus.SUCCESS) {
            return ANSWERABLE;
        }
        if (asset.getParseStatus() == DocumentParseStatus.FAILED
                || asset.getIndexStatus() == DocumentIndexStatus.FAILED) {
            return FAILED;
        }
        return PROCESSING;
    }
}
