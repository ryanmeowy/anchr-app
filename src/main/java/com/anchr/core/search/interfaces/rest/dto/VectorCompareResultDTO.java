package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Vector compare result.
 */
@Data
@Builder
public class VectorCompareResultDTO implements Serializable {

    private String leftType;

    private String rightType;

    /**
     * Cosine similarity in [-1, 1].
     */
    private Double cosineSimilarity;

    /**
     * Display score in [0, 100].
     */
    private Double scorePercent;

    /**
     * Match level, e.g. HIGH/MEDIUM/LOW.
     */
    private String matchLevel;

    private Integer leftDimension;

    private Integer rightDimension;

    private Double leftNorm;

    private Double rightNorm;

    private Boolean leftCacheHit;

    private Boolean rightCacheHit;

    private Long elapsedMs;
}
