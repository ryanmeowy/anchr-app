package com.anchr.core.conversation.application.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Generated answer result with fallback metadata.
 */
@Data
public class AnswerGenerationResult {

    private String answerText;
    private boolean fallbackUsed;
    private boolean generationFailed;
    /**
     * True when the answer is a clarifying question instead of a grounded answer.
     * Such answers keep their branch citations so clients can resolve the markers in the text.
     */
    private boolean clarificationRequested;
    private String fallbackReason;
    private List<String> answerInputSegmentIds = new ArrayList<>();
}
