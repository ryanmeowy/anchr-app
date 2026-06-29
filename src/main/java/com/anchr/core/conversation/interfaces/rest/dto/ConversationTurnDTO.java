package com.anchr.core.conversation.interfaces.rest.dto;

import com.anchr.core.conversation.domain.model.ConversationCitation;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Conversation turn response DTO.
 */
@Data
public class ConversationTurnDTO implements Serializable {

    private String turnId;
    private String sessionId;
    private String query;
    private String rewrittenQuery;
    private String answer;
    private List<String> kbScope;
    private String answerMode;
    private List<CitationDTO> citations;
    private List<ResultCardDTO> resultCards;
    private long createdAt;

    @Data
    public static class CitationDTO implements Serializable {
        private String fileName;
        private Integer pageNo;
        private String snippet;
        private String hitType;
        private String assetId;
        private String segmentId;
        private ConversationCitation.CitationWhy why;
    }
}
