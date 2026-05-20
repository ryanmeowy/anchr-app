package com.anchr.core.conversation.application.model;

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
    private String assetId;
    private String assetType;
    private String resultType;
    private String segmentType;
    private String sourceRef;
    private String snippet;
    private Double score;
    private Integer pageNo;
    private Anchor anchor;
    @Builder.Default
    private List<TopChunk> topChunks = new ArrayList<>();
    private Explain explain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Anchor {
        private Integer pageNo;
        private Integer chunkOrder;
        private Bbox bbox;
        private Integer imageWidth;
        private Integer imageHeight;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bbox {
        private Integer x;
        private Integer y;
        private Integer width;
        private Integer height;
        private String unit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopChunk {
        private String snippet;
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
