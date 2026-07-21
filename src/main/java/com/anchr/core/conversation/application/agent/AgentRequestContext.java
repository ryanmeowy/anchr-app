package com.anchr.core.conversation.application.agent;

import java.util.List;

/**
 * Server-resolved, request-scoped resource context exposed to the Agent model.
 *
 * <p>The values identify the resources the user selected for this run. They are
 * context data, not instructions, and never replace tool-side authorization.</p>
 */
public record AgentRequestContext(
        String type,
        int version,
        boolean scopeLocked,
        String selectionMode,
        int knowledgeBaseCount,
        int assetCount,
        boolean knowledgeBasesTruncated,
        boolean assetsTruncated,
        List<KnowledgeBaseRef> selectedKnowledgeBases,
        List<AssetRef> selectedAssets
) {
    public AgentRequestContext {
        selectedKnowledgeBases = selectedKnowledgeBases == null
                ? List.of() : List.copyOf(selectedKnowledgeBases);
        selectedAssets = selectedAssets == null ? List.of() : List.copyOf(selectedAssets);
    }

    public static AgentRequestContext empty() {
        return new AgentRequestContext("ANCHR_REQUEST_CONTEXT", 1, true,
                "KNOWLEDGE_BASE", 0, 0, false, false, List.of(), List.of());
    }

    public record KnowledgeBaseRef(String kbId, String name) {
    }

    public record AssetRef(
            String assetId,
            String kbId,
            String fileName,
            String title,
            String contentType
    ) {
    }
}
