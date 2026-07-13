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
        private String reason;
        private List<CitationChunkSnapshotDTO> chunks;
    }
}
