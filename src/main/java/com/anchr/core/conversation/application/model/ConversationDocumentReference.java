package com.anchr.core.conversation.application.model;

/** Ask-owned reference to one currently active document. */
public record ConversationDocumentReference(
        String id,
        String kbId,
        String fileName,
        String title,
        String fileType,
        String mimeType,
        long activeIndexGeneration,
        int segmentCount
) {
}
