package com.anchr.core.search.interfaces.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable preview snapshot for one chunk in an asset-level citation group.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationChunkSnapshotDTO implements Serializable {

    private String segmentId;
    private Integer segmentIndex;
    private String citationLabel;
    private String title;
    private Integer pageNo;
    private Integer chunkOrder;
    private String content;
    private String snippet;
    private String hitType;
    private PreviewAnchorDTO anchor;
    private WhyDTO why;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WhyDTO implements Serializable {
        private Double score;
        @Builder.Default
        private List<String> hitSources = new ArrayList<>();
        private MatchedByDTO matchedBy;
        private String matchSummary;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchedByDTO implements Serializable {
        private Boolean vector;
        private Boolean title;
        private Boolean content;
        private Boolean ocr;
    }
}
