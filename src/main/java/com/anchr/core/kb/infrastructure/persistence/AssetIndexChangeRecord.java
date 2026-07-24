package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for the append-only asset_index_change table.
 */
@Data
public class AssetIndexChangeRecord {

    private Long revision;
    private String eventId;
    private String kbId;
    private String assetId;
    private String operation;
    private Long indexGeneration;
    private LocalDateTime occurredAt;
    private String createdBy;
}
