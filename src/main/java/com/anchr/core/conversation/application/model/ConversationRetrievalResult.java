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
}
