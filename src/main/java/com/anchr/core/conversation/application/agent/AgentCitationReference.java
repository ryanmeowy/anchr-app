package com.anchr.core.conversation.application.agent;

public record AgentCitationReference(int assetIndex, int segmentIndex, String segmentId) {
    public String label() {
        return assetIndex + "-" + segmentIndex;
    }
}
