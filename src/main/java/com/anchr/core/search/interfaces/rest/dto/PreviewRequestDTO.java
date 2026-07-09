package com.anchr.core.search.interfaces.rest.dto;

import lombok.Data;

import java.util.List;

@Data
public class PreviewRequestDTO {
    private String recordId;
    private String sourceType;
    private String sourceId;
    private String sessionId;
    private String question;
    private CitationInfo citationInfo;

    @Data
    public static class CitationInfo {
        private String segmentId;
        private String citationIndex;
        private CitationReason why;
        private String reason;
    }

    @Data
    public static class CitationReason {
        private String score;
        private List<String> hitSources;
        private String matchSummary;
    }
}
