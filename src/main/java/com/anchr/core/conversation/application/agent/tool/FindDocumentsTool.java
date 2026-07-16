package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FindDocumentsTool implements AgentTool<FindDocumentsTool.Input> {
    public record Input(@NotBlank @Size(max = 500) String query, @Min(1) @Max(10) Integer limit) {}

    private final AssetRepository assetRepository;
    private final ConversationRetrievalOrchestrator retrievalOrchestrator;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "find_documents"; }
    @Override public String description() {
        return "根据文件名、标题或内容描述，在当前授权范围内寻找最相关的文档。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        int limit = input.limit() == null ? 5 : input.limit();
        LinkedHashMap<String, DocumentMatch> matches = new LinkedHashMap<>();
        for (String kbId : context.kbIds()) {
            for (Asset asset : assetRepository.listActive(kbId, input.query().trim(), null, limit, 0)) {
                if (allowed(asset.getId(), context)) matches.putIfAbsent(asset.getId(), new DocumentMatch(asset));
            }
        }
        var retrieved = retrievalOrchestrator.retrieve(input.query().trim(), 10, context.kbIds(),
                List.of("MIXED"), context.assetIds());
        List<ConversationRetrievalCandidate> evidence = new ArrayList<>();
        for (ConversationRetrievalCandidate candidate : retrieved.getTopCandidates()) {
            if (!StringUtils.hasText(candidate.getAssetId()) || !allowed(candidate.getAssetId(), context)) continue;
            evidence.add(candidate);
            DocumentMatch match = matches.computeIfAbsent(candidate.getAssetId(), id -> load(candidate, context));
            if (match != null) match.add(candidate);
        }
        List<DocumentMatch> ranked = matches.values().stream().filter(java.util.Objects::nonNull)
                .sorted((a, b) -> Double.compare(b.score, a.score)).limit(limit).toList();
        try {
            return AgentToolResult.success(objectMapper.writeValueAsString(Map.of(
                    "success", true, "documents", ranked.stream().map(DocumentMatch::view).toList())), evidence,
                    Map.of("documentCount", ranked.size(), "evidenceCount", evidence.size()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool result", e);
        }
    }

    private DocumentMatch load(ConversationRetrievalCandidate candidate, AgentExecutionContext context) {
        for (String kbId : context.kbIds()) {
            Asset asset = assetRepository.findActiveById(kbId, candidate.getAssetId()).orElse(null);
            if (asset != null) return new DocumentMatch(asset);
        }
        return null;
    }

    private boolean allowed(String assetId, AgentExecutionContext context) {
        return context.assetIds().isEmpty() || context.assetIds().contains(assetId);
    }

    private static final class DocumentMatch {
        private final Asset asset;
        private double score = 1.0D;
        private String segmentId = "";
        private String snippet = "";

        private DocumentMatch(Asset asset) { this.asset = asset; }
        private void add(ConversationRetrievalCandidate value) {
            score = Math.max(score, value.getScore() == null ? 0.0D : value.getScore());
            if (segmentId.isEmpty()) segmentId = value.getSegmentId() == null ? "" : value.getSegmentId();
            if (snippet.isEmpty()) {
                String text = StringUtils.hasText(value.getSnippet()) ? value.getSnippet() : value.getContent();
                snippet = text == null ? "" : text.substring(0, Math.min(500, text.length()));
            }
        }
        private Map<String, Object> view() {
            return Map.of("assetId", asset.getId(), "kbId", asset.getKbId(),
                    "fileName", asset.getFileName() == null ? "" : asset.getFileName(), "title", asset.getTitle() == null ? "" : asset.getTitle(),
                    "fileType", asset.getFileType() == null ? "" : asset.getFileType(), "segmentCount", asset.getSegmentCount(),
                    "matchedSegmentId", segmentId, "matchSnippet", snippet);
        }
    }
}
