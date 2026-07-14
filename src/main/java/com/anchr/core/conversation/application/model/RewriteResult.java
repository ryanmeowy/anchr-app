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
    private String rewrittenQuery;
    private String rewriteReason;
    private List<String> topicEntities = new ArrayList<>();
    private double confidence;
    private boolean fallbackUsed;
}
