package com.anchr.core.kb.domain.model;

/**
 * Immutable payload for deleting one inactive logical generation from search.
 */
public record DocumentIndexGenerationDeletePayload(
        String kbId,
        String assetId,
        long indexGeneration) {
}
