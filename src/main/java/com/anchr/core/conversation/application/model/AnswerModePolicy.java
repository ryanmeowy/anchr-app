package com.anchr.core.conversation.application.model;

/**
 * Mode-specific grounded answer generation policy.
 */
public record AnswerModePolicy(
        int groundingLimit,
        int minEvidenceChars,
        double minTopScore,
        boolean allowSpeculation,
        String styleInstruction
) {
}
