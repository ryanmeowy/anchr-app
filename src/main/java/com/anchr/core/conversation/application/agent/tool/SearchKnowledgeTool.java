package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.QueryRewriteService;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SearchKnowledgeTool implements AgentTool<SearchKnowledgeTool.Input> {
    public record Input(@NotBlank @Size(max = 1_000) String query,
                        @Size(max = 100) List<String> assetIds,
                        @Min(1) @Max(10) Integer limit,
                        @Size(max = 3) List<String> modalities) {
    }

    private final QueryRewriteService queryRewriteService;
    private final ConversationRetrievalOrchestrator retrievalOrchestrator;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "search_knowledge"; }
    @Override public String description() {
        return "回答定义、原理、工作机制、流程、规则、配置和其他定向问题时，在当前授权知识库或指定 assetIds 内检索最相关内容并返回可引用证据；"
                + "即使只选中一份文档，定向问题也应优先使用本工具，而不是分页通读全文。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        List<String> assets = resolveAssets(input.assetIds(), context);
        var rewrite = queryRewriteService.rewrite(context.sessionId(), input.query().trim());
        var retrieval = retrievalOrchestrator.retrieve(
                rewrite.getRewrittenQuery(), input.limit() == null ? 8 : input.limit(), context.kbIds(),
                normalizeModalities(input.modalities()), assets);
        List<ConversationRetrievalCandidate> evidence = retrieval.getTopCandidates() == null
                ? List.of()
                : retrieval.getTopCandidates().stream()
                        .filter(candidate -> !SegmentType.isImageVisual(
                                candidate.getSegmentType()))
                        .toList();
        try {
            return AgentToolResult.success(objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "rewrittenQuery", rewrite.getRewrittenQuery(),
                    "evidence", evidence.stream().map(this::view).toList())), evidence,
                    Map.of("evidenceCount", evidence.size()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool result", e);
        }
    }

    private List<String> resolveAssets(List<String> requested, AgentExecutionContext context) {
        if (requested == null || requested.isEmpty()) return context.assetIds();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String id : requested) {
            if (!StringUtils.hasText(id)) continue;
            String normalized = id.trim();
            if (!context.assetIds().isEmpty() && !context.assetIds().contains(normalized)) {
                throw new SecurityException("PERMISSION_DENIED");
            }
            values.add(normalized);
        }
        return values.stream().toList();
    }

    private List<String> normalizeModalities(List<String> source) {
        if (source == null || source.isEmpty()) return List.of("MIXED");
        return source.stream().filter(StringUtils::hasText).map(String::trim)
                .map(String::toUpperCase).filter(v -> List.of("TEXT", "IMAGE", "MIXED").contains(v))
                .distinct().toList();
    }

    private Map<String, Object> view(ConversationRetrievalCandidate item) {
        return Map.of(
                "segmentId", safe(item.getSegmentId()), "assetId", safe(item.getAssetId()),
                "title", safe(item.getTitle()), "pageNo", item.getPageNo() == null ? -1 : item.getPageNo(),
                "content", clip(StringUtils.hasText(item.getContent()) ? item.getContent() : item.getSnippet(), 2_000));
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String clip(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
