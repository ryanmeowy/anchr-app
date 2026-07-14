package com.anchr.core.kb.domain.model;

/**
 * Immutable payload for deleting all search segments of a document.
 */
public record DocumentIndexDeletePayload(String kbId, String assetId) {
}
