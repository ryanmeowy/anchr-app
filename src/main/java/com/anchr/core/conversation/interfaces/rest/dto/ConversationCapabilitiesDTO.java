package com.anchr.core.conversation.interfaces.rest.dto;

public record ConversationCapabilitiesDTO(boolean agentAvailable,
                                          int maxDocumentsPerSummary) {
}
