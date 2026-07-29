package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentToolException;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentScopeGuard {
    private static final int NAME_LOOKUP_LIMIT = 50;
    private final ConversationKnowledgeAcl conversationKnowledgeAcl;

    /**
     * Resolves an exact asset id first, then a unique exact file name/title inside the server-authorized scope.
     */
    public ConversationDocumentReference requireAsset(
            String reference, AgentExecutionContext context) {
        if (!StringUtils.hasText(reference)) {
            throw new AgentToolException("INVALID_ARGUMENTS", "document reference cannot be blank");
        }
        String normalized = normalizeReference(reference);
        ConversationDocumentReference byId = findById(normalized, context.kbIds());
        if (byId != null) {
            if (!isAssetAllowed(byId.id(), context)) {
                throw new AgentToolException("PERMISSION_DENIED", "document is outside the request asset scope");
            }
            return byId;
        }

        List<ConversationDocumentReference> nameMatches = context.assetIds().isEmpty()
                ? findByNameInKnowledgeBases(normalized, context.kbIds())
                : findByNameInExplicitAssetScope(normalized, context);
        if (nameMatches.size() == 1) return nameMatches.getFirst();
        if (nameMatches.size() > 1) {
            throw new AgentToolException("AMBIGUOUS_DOCUMENT",
                    "multiple documents match this name; call find_documents and reuse documents[].assetId");
        }
        if (!context.assetIds().isEmpty()) {
            throw new AgentToolException("PERMISSION_DENIED", "document is outside the request asset scope");
        }
        throw new AgentToolException("DOCUMENT_NOT_FOUND",
                "no document matches this reference in the authorized knowledge bases; call find_documents and reuse documents[].assetId");
    }

    private ConversationDocumentReference findById(String assetId, List<String> kbIds) {
        return conversationKnowledgeAcl.findActiveDocument(kbIds, assetId).orElse(null);
    }

    private List<ConversationDocumentReference> findByNameInKnowledgeBases(
            String reference, List<String> kbIds) {
        Map<String, ConversationDocumentReference> matches = new LinkedHashMap<>();
        for (ConversationDocumentReference asset : conversationKnowledgeAcl
                .searchActiveDocuments(kbIds, reference, NAME_LOOKUP_LIMIT)) {
            if (matchesName(asset, reference)) {
                matches.putIfAbsent(asset.id(), asset);
            }
        }
        return matches.values().stream().toList();
    }

    private List<ConversationDocumentReference> findByNameInExplicitAssetScope(
            String reference, AgentExecutionContext context) {
        Map<String, ConversationDocumentReference> matches = new LinkedHashMap<>();
        for (String assetId : context.assetIds()) {
            ConversationDocumentReference asset = findById(assetId, context.kbIds());
            if (asset != null && matchesName(asset, reference)) {
                matches.putIfAbsent(asset.id(), asset);
            }
        }
        return matches.values().stream().toList();
    }

    private boolean matchesName(ConversationDocumentReference asset, String reference) {
        String expected = normalizedName(reference);
        return expected.equals(normalizedName(asset.fileName()))
                || expected.equals(normalizedName(asset.title()));
    }

    private boolean isAssetAllowed(String assetId, AgentExecutionContext context) {
        return context.assetIds().isEmpty() || context.assetIds().contains(assetId);
    }

    private String normalizeReference(String value) {
        String result = value.trim();
        if (result.length() >= 2 && ((result.startsWith("《") && result.endsWith("》"))
                || (result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))
                || (result.startsWith("`") && result.endsWith("`")))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private String normalizedName(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
