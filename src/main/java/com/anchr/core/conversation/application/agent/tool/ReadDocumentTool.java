package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.anchr.core.conversation.application.constant.AgentConstant.READ_DOCUMENT_DEFAULT_PAGE_SIZE;
import static com.anchr.core.conversation.application.constant.AgentConstant.READ_DOCUMENT_MAX_CONTENT_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.READ_DOCUMENT_MAX_PAGE_SIZE;
import static com.anchr.core.conversation.application.constant.AgentConstant.READ_DOCUMENT_MIN_PAGE_SIZE;

@Component
@RequiredArgsConstructor
public class ReadDocumentTool implements AgentTool<ReadDocumentTool.Input> {
    public record Input(
            @JsonPropertyDescription("优先填写 find_documents 返回的 documents[].assetId；也允许当前授权范围内唯一匹配的完整文件名或标题。不得填写 segmentId。")
            @NotBlank String assetId,
            String cursor,
            @Min(1) @Max(READ_DOCUMENT_MAX_PAGE_SIZE) Integer limit) {}

    private final AgentScopeGuard scopeGuard;
    private final ConversationRetrievalAcl conversationRetrievalAcl;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "read_document"; }
    @Override public String description() {
        return "仅在需要连续上下文或相邻原文时，按原始顺序分页读取指定文档；定向事实问题优先使用 search_knowledge。"
                + "优先原样复用 find_documents 返回的 documents[].assetId，不能使用 segmentId；每次建议读取 20 个片段，需要继续时使用 nextCursor。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        var asset = scopeGuard.requireAsset(input.assetId(), context);
        Cursor cursor = decode(input.cursor());
        int limit = input.limit() == null
                ? READ_DOCUMENT_DEFAULT_PAGE_SIZE
                : Math.max(READ_DOCUMENT_MIN_PAGE_SIZE, input.limit());
        List<ConversationRetrievalCandidate> candidates = conversationRetrievalAcl.readDocument(
                asset, cursor.chunkOrder(), cursor.segmentId(), limit + 1);
        boolean hasMore = candidates.size() > limit;
        List<ConversationRetrievalCandidate> page = hasMore
                ? candidates.subList(0, limit) : candidates;
        int chars = 0;
        java.util.ArrayList<ConversationRetrievalCandidate> bounded = new java.util.ArrayList<>();
        for (ConversationRetrievalCandidate candidate : page) {
            String text = candidate.getContent() == null ? "" : candidate.getContent();
            if (!bounded.isEmpty() && chars + text.length() > READ_DOCUMENT_MAX_CONTENT_CHARS) break;
            chars += text.length();
            bounded.add(candidate);
        }
        String next = null;
        if ((hasMore || bounded.size() < page.size()) && !bounded.isEmpty()) {
            ConversationRetrievalCandidate last = bounded.getLast();
            next = encode(last.getAnchor() == null ? null : last.getAnchor().getChunkOrder(),
                    last.getSegmentId());
        }
        List<ConversationRetrievalCandidate> evidence = List.copyOf(bounded);
        try {
            return AgentToolResult.success(objectMapper.writeValueAsString(Map.of(
                    "success", true, "assetId", asset.id(), "fileName", asset.fileName(),
                    "segments", evidence.stream().map(this::view).toList(),
                    "nextCursor", next == null ? "" : next, "hasMore", next != null)), evidence,
                    Map.of("segmentCount", evidence.size(), "evidenceCount", evidence.size(), "hasMore", next != null));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool result", e);
        }
    }

    private Map<String, Object> view(ConversationRetrievalCandidate value) {
        return Map.of("segmentId", value.getSegmentId(), "title", value.getTitle() == null ? "" : value.getTitle(),
                "pageNo", value.getPageNo() == null ? -1 : value.getPageNo(), "content", value.getContent());
    }

    private Cursor decode(String raw) {
        if (!StringUtils.hasText(raw)) return new Cursor(null, null);
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int split = decoded.indexOf(':');
            return new Cursor(Integer.valueOf(decoded.substring(0, split)), decoded.substring(split + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private String encode(Integer order, String id) {
        String raw = (order == null ? Integer.MAX_VALUE : order) + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Integer chunkOrder, String segmentId) {}
}
