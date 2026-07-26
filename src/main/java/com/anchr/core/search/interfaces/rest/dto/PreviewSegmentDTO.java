package com.anchr.core.search.interfaces.rest.dto;

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
    private String kbId;
    private String kbName;
    private String assetType;
    private String segmentType;
    private String fileName;
    private String previewType;
    private String previewUrl;
    private Long expiresAt;
    private String imagePreviewUrl;
    private Long imagePreviewExpiresAt;
    private String sourceRef;
    private String thumbnail;
    private String title;
    private String content;
    private String ocrSummary;
    private PreviewAnchorDTO anchor;
    private CitationContextDTO citationContext;
    private String sourceType;
    private String sourceId;
    private String sessionId;
    private String sourceQuestion;

    @Data
    @Builder
    public static class CitationContextDTO implements Serializable {
        private String citationIndex;
        private String citationReason;
    }
}
