package com.smart.vision.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Segment preview response.
 */
@Data
@Builder
public class PreviewSegmentDTO implements Serializable {

    private String segmentId;
    private String assetId;
    private String assetType;
    private String segmentType;
    private String fileName;
    private String previewType;
    private String previewUrl;
    private Long expiresAt;
    private String sourceRef;
    private String thumbnail;
    private String title;
    private String snippet;
    private String ocrSummary;
    private PreviewAnchorDTO anchor;
    private List<SurroundingChunkDTO> surroundingChunks;
}
