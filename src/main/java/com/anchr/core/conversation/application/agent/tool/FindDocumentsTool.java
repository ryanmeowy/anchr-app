package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
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

    private final ConversationKnowledgeAcl conversationKnowledgeAcl;
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
        for (ConversationDocumentReference asset : conversationKnowledgeAcl
                .searchActiveDocuments(context.kbIds(), input.query().trim(), limit)) {
            if (allowed(asset.id(), context)) {
                matches.putIfAbsent(asset.id(), new DocumentMatch(asset));
            }
        }
        var retrieved = retrievalOrchestrator.retrieve(input.query().trim(), 10, context.kbIds(),
                List.of("MIXED"), context.assetIds());
        List<ConversationRetrievalCandidate> evidence = new ArrayList<>();
        for (ConversationRetrievalCandidate candidate : retrieved.getTopCandidates()) {
            if (!StringUtils.hasText(candidate.getAssetId()) || !allowed(candidate.getAssetId(), context)) continue;
            if (candidate.isCitableEvidence()) {
                evidence.add(candidate);
            }
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
        return conversationKnowledgeAcl.findActiveDocument(
                context.kbIds(), candidate.getAssetId())
                .map(DocumentMatch::new)
                .orElse(null);
    }

    private boolean allowed(String assetId, AgentExecutionContext context) {
        return context.assetIds().isEmpty() || context.assetIds().contains(assetId);
    }

    private static final class DocumentMatch {
        private final ConversationDocumentReference asset;
        private double score = 1.0D;
        private String segmentId = "";
        private String snippet = "";

        private DocumentMatch(ConversationDocumentReference asset) { this.asset = asset; }
        private void add(ConversationRetrievalCandidate value) {
            score = Math.max(score, value.getScore() == null ? 0.0D : value.getScore());
            if (segmentId.isEmpty()) segmentId = value.getSegmentId() == null ? "" : value.getSegmentId();
            if (snippet.isEmpty()) {
                String text = StringUtils.hasText(value.getSnippet()) ? value.getSnippet() : value.getContent();
                snippet = text == null ? "" : text.substring(0, Math.min(500, text.length()));
            }
        }
        private Map<String, Object> view() {
            return Map.of("assetId", asset.id(), "kbId", asset.kbId(),
                    "fileName", asset.fileName() == null ? "" : asset.fileName(), "title", asset.title() == null ? "" : asset.title(),
                    "fileType", asset.fileType() == null ? "" : asset.fileType(), "segmentCount", asset.segmentCount(),
                    "matchedSegmentId", segmentId, "matchSnippet", snippet);
        }
    }
}
