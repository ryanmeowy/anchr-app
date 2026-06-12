package com.anchr.core.search.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import com.anchr.core.common.model.Bbox;

/**
 * Unified retrieval unit for both text and image assets.
 */
@Value
@Builder
public class Segment {

    String segmentId;
    String kbId;
    String assetId;
    AssetType assetType;
    SegmentType segmentType;
    String title;
    String contentText;
    String ocrText;
    Integer pageNo;
    Integer chunkOrder;
    Bbox bbox;
    Integer imageWidth;
    Integer imageHeight;
    List<Float> embedding;
    String sourceRef;
    String thumbnail;
    String ocrSummary;
    List<String> tags;
    Long createdAt;
}
