package com.anchr.core.conversation.application.model;

import com.anchr.core.common.model.BboxInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversation-side retrieval candidate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRetrievalCandidate {

    private String segmentId;
    private String kbId;
    private String assetId;
    private String assetType;
    private String resultType;
    private String segmentType;
    private String sourceRef;
    /**
     * Original segment content used as grounding evidence for answer generation.
     */
    private String content;
    private String snippet;
    private Double score;
    private Integer pageNo;
    private Anchor anchor;
    private Explain explain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Anchor {
        private Integer pageNo;
        private Integer chunkOrder;
        private List<BboxInfo> bbox;
        private Integer imageWidth;
        private Integer imageHeight;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Explain {
        private String strategyEffective;
        @Builder.Default
        private List<String> hitSources = new ArrayList<>();
    }
}
