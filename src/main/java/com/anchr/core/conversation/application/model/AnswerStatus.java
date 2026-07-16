package com.anchr.core.conversation.application.model;

/**
 * Outcome of conversation answer generation.
 */
public enum AnswerStatus {
    ANSWERED,
    PROCESSING,
    CANCELLED,
    NO_EVIDENCE,
    MODEL_FALLBACK;

    public static AnswerStatus from(AnswerGenerationResult result) {
        if (result == null || !result.isFallbackUsed()) {
            return ANSWERED;
        }
        String reason = result.getFallbackReason();
        if (reason != null && reason.startsWith("no_evidence")) {
            return NO_EVIDENCE;
        }
        return MODEL_FALLBACK;
    }
}
