package com.anchr.core.kb.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable payload for deleting all search segments and stored objects of a document.
 *
 * <p>The object keys are nullable so events written by older releases can still be
 * processed; those events only clean the search projection.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentIndexDeletePayload(
        String kbId,
        String assetId,
        String objectKey,
        String previewObjectKey
) {
}
