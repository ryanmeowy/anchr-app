package com.smart.vision.core.search.interfaces.rest.dto;

import com.smart.vision.core.search.domain.model.Bbox;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

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
    private String previewType;
    private String previewUrl;
    private String sourceRef;
    private String thumbnail;
    private String title;
    private String snippet;
    private String ocrSummary;
    private Anchor anchor;

    @Data
    @Builder
    public static class Anchor implements Serializable {
        private Integer pageNo;
        private Integer chunkOrder;
        private Bbox bbox;
        private Integer imageWidth;
        private Integer imageHeight;
    }
}
