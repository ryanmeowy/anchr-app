package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

record UnverifiedAgentAnswer(
        AgentFinalAnswer value,
        String validationToolCallId,
        String validationToolName
) {
}

sealed interface VerifiedAgentAnswer
        permits VerifiedPlainAnswer, VerifiedNoEvidenceAnswer, VerifiedCitedAnswer {
    String answer();
}

record VerifiedPlainAnswer(String answer) implements VerifiedAgentAnswer {
}

record VerifiedNoEvidenceAnswer(String answer) implements VerifiedAgentAnswer {
}

record VerifiedCitedAnswer(
        String answer,
        List<ConversationCitation> citations,
        List<ConversationRetrievalCandidate> citedEvidence
) implements VerifiedAgentAnswer {
    VerifiedCitedAnswer {
        citations = List.copyOf(citations);
        citedEvidence = List.copyOf(citedEvidence);
    }
}

record PresentedAgentAnswer(
        String answer,
        AnswerStatus answerStatus,
        String fallbackReason,
        List<ConversationCitation> citations
) {
    PresentedAgentAnswer {
        citations = List.copyOf(citations);
    }
}

sealed interface AgentAnswerValidationOutcome
        permits AgentAnswerValidationOutcome.Verified, AgentAnswerValidationOutcome.Rejected {
    record Verified(VerifiedAgentAnswer answer) implements AgentAnswerValidationOutcome {
    }

    record Rejected(String code, String message, String fallbackReason)
            implements AgentAnswerValidationOutcome {
    }
}
