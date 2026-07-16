package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentToolException;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
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
    private final AssetRepository assetRepository;

    /**
     * Resolves an exact asset id first, then a unique exact file name/title inside the server-authorized scope.
     */
    public Asset requireAsset(String reference, AgentExecutionContext context) {
        if (!StringUtils.hasText(reference)) {
            throw new AgentToolException("INVALID_ARGUMENTS", "document reference cannot be blank");
        }
        String normalized = normalizeReference(reference);
        Asset byId = findById(normalized, context.kbIds());
        if (byId != null) {
            if (!isAssetAllowed(byId.getId(), context)) {
                throw new AgentToolException("PERMISSION_DENIED", "document is outside the request asset scope");
            }
            return byId;
        }

        List<Asset> nameMatches = context.assetIds().isEmpty()
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

    private Asset findById(String assetId, List<String> kbIds) {
        for (String kbId : kbIds) {
            Asset asset = assetRepository.findActiveById(kbId, assetId).orElse(null);
            if (asset != null) return asset;
        }
        return null;
    }

    private List<Asset> findByNameInKnowledgeBases(String reference, List<String> kbIds) {
        Map<String, Asset> matches = new LinkedHashMap<>();
        for (String kbId : kbIds) {
            for (Asset asset : assetRepository.listActive(kbId, reference, null, NAME_LOOKUP_LIMIT, 0)) {
                if (matchesName(asset, reference)) matches.putIfAbsent(asset.getId(), asset);
            }
        }
        return matches.values().stream().toList();
    }

    private List<Asset> findByNameInExplicitAssetScope(String reference, AgentExecutionContext context) {
        Map<String, Asset> matches = new LinkedHashMap<>();
        for (String assetId : context.assetIds()) {
            Asset asset = findById(assetId, context.kbIds());
            if (asset != null && matchesName(asset, reference)) matches.putIfAbsent(asset.getId(), asset);
        }
        return matches.values().stream().toList();
    }

    private boolean matchesName(Asset asset, String reference) {
        String expected = normalizedName(reference);
        return expected.equals(normalizedName(asset.getFileName()))
                || expected.equals(normalizedName(asset.getTitle()));
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
