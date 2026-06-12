package com.anchr.core.search.interfaces.rest.dto;

import com.anchr.core.common.model.Bbox;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Unified kb search response item.
 */
@Data
@Builder
public class SearchResultDTO implements Serializable {

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
    private SearchExplainDTO explain;
    private Anchor anchor;
    private String thumbnail;
    private String ocrSummary;
    private Integer totalHits;
    private List<TopChunk> topChunks;

    /**
     * Optional trace fields for callback to original asset.
     */
    private String segmentId;
    private String kbId;
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
        private String kbId;
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
