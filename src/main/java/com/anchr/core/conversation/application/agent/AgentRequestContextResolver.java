package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationKnowledgeBaseReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_CONTEXT_ASSETS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_CONTEXT_KNOWLEDGE_BASES;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_CONTEXT_NAME_LENGTH;

/**
 * Resolves client-provided IDs back to active server-side resources before
 * exposing their metadata to the Agent model.
 */
@Component
@RequiredArgsConstructor
public class AgentRequestContextResolver {
    private final ConversationKnowledgeAcl conversationKnowledgeAcl;

    public AgentRequestContext resolve(AgentRunRequest request) {
        List<String> requestedKbIds = normalizedIds(request.request().getKbIds());
        List<String> requestedAssetIds = normalizedIds(request.request().getAssetIdList());

        List<ConversationKnowledgeBaseReference> activeKnowledgeBases =
                conversationKnowledgeAcl.resolveVisibleKnowledgeBases(requestedKbIds);
        List<String> authorizedKbIds = activeKnowledgeBases.stream()
                .map(ConversationKnowledgeBaseReference::id)
                .toList();

        List<ConversationDocumentReference> resolvedAssets = resolveAssets(
                requestedAssetIds, authorizedKbIds);
        List<AgentRequestContext.KnowledgeBaseRef> knowledgeBases = activeKnowledgeBases.stream()
                .limit(MAX_CONTEXT_KNOWLEDGE_BASES)
                .map(kb -> new AgentRequestContext.KnowledgeBaseRef(
                        kb.id(), safeText(kb.name())))
                .toList();
        List<AgentRequestContext.AssetRef> assets = resolvedAssets.stream()
                .limit(MAX_CONTEXT_ASSETS)
                .map(asset -> new AgentRequestContext.AssetRef(
                        asset.id(),
                        asset.kbId(),
                        safeText(asset.fileName()),
                        safeText(asset.title()),
                        safeText(StringUtils.hasText(asset.mimeType())
                                ? asset.mimeType() : asset.fileType())))
                .toList();

        return new AgentRequestContext(
                "ANCHR_REQUEST_CONTEXT",
                1,
                true,
                requestedAssetIds.isEmpty() ? "KNOWLEDGE_BASE" : "ASSET",
                authorizedKbIds.size(),
                resolvedAssets.size(),
                authorizedKbIds.size() > knowledgeBases.size(),
                resolvedAssets.size() > assets.size(),
                knowledgeBases,
                assets
        );
    }

    private List<ConversationDocumentReference> resolveAssets(
            List<String> assetIds, List<String> kbIds) {
        List<ConversationDocumentReference> assets = new ArrayList<>();
        for (String assetId : assetIds) {
            conversationKnowledgeAcl.findActiveDocument(kbIds, assetId)
                    .ifPresent(assets::add);
        }
        return assets;
    }

    private List<String> normalizedIds(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) result.add(value.trim());
        }
        return result.stream().toList();
    }

    private String safeText(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim()
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return normalized.length() <= MAX_CONTEXT_NAME_LENGTH
                ? normalized : normalized.substring(0, MAX_CONTEXT_NAME_LENGTH);
    }
}
