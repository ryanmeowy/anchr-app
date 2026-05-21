package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Search answer response.
 */
@Data
@Builder
public class KbAnswerDTO implements Serializable {

    private String answer;
    private List<CitationDTO> citations;
    private List<KbSearchResultDTO> results;
    private AnswerTraceDTO answerTrace;

    @Data
    @Builder
    public static class CitationDTO implements Serializable {
        private Integer citationIndex;
        private String segmentId;
        private String assetId;
        private String kbId;
        private String fileName;
        private Integer pageNo;
        private String snippet;
    }

    @Data
    @Builder
    public static class AnswerTraceDTO implements Serializable {
        private String mode;
        private boolean grounded;
        private String fallbackReason;
    }
}
