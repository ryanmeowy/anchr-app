package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for conversation message API.
 */
@Data
public class ConversationMessageResponseDTO implements Serializable {

    private String sessionId;
    private String turnId;
    private String rewrittenQuery;
    private String answer;
    private List<String> kbScope;
    private String answerMode;
    private String retrievalStage;
    private List<ConversationTurnDTO.CitationDTO> citations;
    private List<ResultCardDTO> resultCards;
    private RetrievalTraceDTO retrievalTrace;
    private List<String> suggestedQuestions;
    private long createdAt;

    @Data
    public static class RetrievalTraceDTO implements Serializable {
        private Integer limit;
        private String strategyEffective;
        private String rewriteReason;
        private Double rewriteConfidence;
        private Boolean rewriteFallback;
        private Integer retrievedCount;
        private Map<String, Integer> groupedResultCounts;
        private List<String> topSegmentIds;
        private List<String> topHitSources;
        private Boolean answerFallback;
        private String answerFallbackReason;
    }
}
