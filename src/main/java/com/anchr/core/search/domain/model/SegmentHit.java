package com.anchr.core.search.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Retrieval hit from kb_segment index.
 */
@Value
@Builder
public class SegmentHit {
    Segment segment;
    double rawScore;
    Map<String, String> highlights;
    List<String> highlightFields;
}
