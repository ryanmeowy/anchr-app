package com.anchr.core.conversation.interfaces.rest.dto;

import com.anchr.core.conversation.domain.model.ConversationCitation;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
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
    private List<String> assetScope;
    private String answerMode;
    private String answerStatus;
    private String answerFallbackReason;
    private List<CitationDTO> citations;
    private List<ResultCardDTO> resultCards;
    private long createdAt;

    @Data
    public static class CitationDTO implements Serializable {
        private Integer citationIndex;
        private String fileName;
        private String kbId;
        private String assetId;
        private List<CitationChunkDTO> chunks = new ArrayList<>();
    }

    @Data
    public static class CitationChunkDTO implements Serializable {
        private String segmentId;
        private Integer pageNo;
        private Integer chunkOrder;
        private String title;
        private String content;
        private String snippet;
        private String hitType;
        private ConversationCitation.Anchor anchor;
        private ConversationCitation.CitationWhy why;
    }
}
