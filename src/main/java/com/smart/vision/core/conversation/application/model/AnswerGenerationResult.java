package com.smart.vision.core.conversation.application.model;

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
    private String fallbackReason;
    private List<String> answerInputSegmentIds = new ArrayList<>();
}
