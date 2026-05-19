package com.smart.vision.core.search.interfaces.rest.dto;

import com.smart.vision.core.search.domain.model.Bbox;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Unified kb search response item.
 */
@Data
@Builder
public class KbSearchResultDTO implements Serializable {

    /**
     * Unified protocol fields (Phase 2 E1).
     */
    private String segmentType;
    private String content;
    private String resultType;
    private String assetType;
    private String snippet;
    private Integer pageNo;
    private Double score;
    private KbSearchExplainDTO explain;
    private Anchor anchor;
    private String thumbnail;
    private String ocrSummary;
    private Integer totalHits;
    private List<TopChunk> topChunks;

    /**
     * Optional trace fields for callback to original asset.
     */
    private String segmentId;
    private String assetId;
    private String sourceRef;

    @Data
    @Builder
    public static class Anchor implements Serializable {
        private Integer pageNo;
        private Integer chunkOrder;
        private Bbox bbox;
        private Integer imageWidth;
        private Integer imageHeight;
    }

    @Data
    @Builder
    public static class TopChunk implements Serializable {
        private String segmentId;
        private String segmentType;
        private String snippet;
        private Double score;
        private Integer pageNo;
        private Anchor anchor;
        private String sourceRef;
        private String thumbnail;
        private String ocrSummary;
    }
}
