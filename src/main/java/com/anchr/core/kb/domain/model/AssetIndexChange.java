package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Immutable, replayable record of one logical asset-index change.
 */
@Value
@Builder(toBuilder = true)
public class AssetIndexChange {

    Long revision;
    String eventId;
    String kbId;
    String assetId;
    AssetIndexChangeOperation operation;
    long indexGeneration;
    LocalDateTime occurredAt;
    String createdBy;
}
