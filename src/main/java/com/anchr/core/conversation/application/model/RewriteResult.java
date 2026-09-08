package com.anchr.core.conversation.application.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Query rewrite result model for conversation flow.
 */
@Data
public class RewriteResult {

    private String originalQuery;
    /** Full context-resolved question, populated only by traditional RAG rewrite. */
    private String resolvedQuestion;
    /** Text-recall keywords, populated only by traditional RAG rewrite. */
    private List<String> keywords = new ArrayList<>();
    private String rewrittenQuery;
    private String rewriteReason;
    private List<String> topicEntities = new ArrayList<>();
    private double confidence;
    private boolean fallbackUsed;
}
