package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

/**
 * Persistence record for the asset health aggregation query.
 */
@Data
public class AssetHealthStatsRecord {

    private Long total;
    private Long indexed;
    private Long pending;
    private Long failed;
    private Long totalSegments;
    private Long indexedSegments;
}
