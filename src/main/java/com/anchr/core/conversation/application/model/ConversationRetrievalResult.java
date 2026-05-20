package com.anchr.core.conversation.application.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieval output for conversation orchestration.
 */
@Data
public class ConversationRetrievalResult {

    private List<ConversationRetrievalCandidate> topCandidates = new ArrayList<>();
    private List<GroupedResult> groupedResults = new ArrayList<>();

    @Data
    public static class GroupedResult {
        private String groupKey;
        private List<ConversationRetrievalCandidate> items = new ArrayList<>();
    }
}
