package com.anchr.core.search.domain.model;

import java.util.Map;

/**
 * Unified segment candidate for post-RRF rerank and response assembly.
 */
public record SegmentRerankCandidate(
        String segmentId,
        Segment segment,
        Map<String, String> highlights,
        double score,
        double bestRawScore,
        int hitCount,
        boolean vectorHit
) {
}
