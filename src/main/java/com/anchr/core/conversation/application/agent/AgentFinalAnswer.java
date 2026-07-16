package com.anchr.core.conversation.application.agent;

import java.util.List;

public record AgentFinalAnswer(String answer, List<String> citedSegmentIds) {
    public AgentFinalAnswer {
        citedSegmentIds = citedSegmentIds == null ? List.of() : List.copyOf(citedSegmentIds);
    }
}
