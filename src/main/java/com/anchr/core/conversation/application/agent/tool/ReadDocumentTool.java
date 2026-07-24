package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.repository.SegmentRepository;
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

@Component
@RequiredArgsConstructor
public class ReadDocumentTool implements AgentTool<ReadDocumentTool.Input> {
    private static final int MAX_CONTENT_CHARS = 20_000;
    private static final int MIN_PAGE_SIZE = 10;
    public record Input(
            @JsonPropertyDescription("优先填写 find_documents 返回的 documents[].assetId；也允许当前授权范围内唯一匹配的完整文件名或标题。不得填写 segmentId。")
            @NotBlank String assetId,
            String cursor,
            @Min(1) @Max(20) Integer limit) {}

    private final AgentScopeGuard scopeGuard;
    private final SegmentRepository segmentRepository;
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
        int limit = input.limit() == null ? 20 : Math.max(MIN_PAGE_SIZE, input.limit());
        List<Segment> segments = segmentRepository.listByAssetId(asset.getKbId(), asset.getId(),
                asset.getActiveIndexGeneration(), cursor.chunkOrder(), cursor.segmentId(), limit + 1);
        boolean hasMore = segments.size() > limit;
        List<Segment> page = hasMore ? segments.subList(0, limit) : segments;
        int chars = 0;
        java.util.ArrayList<Segment> bounded = new java.util.ArrayList<>();
        for (Segment segment : page) {
            String text = content(segment);
            if (!bounded.isEmpty() && chars + text.length() > MAX_CONTENT_CHARS) break;
            chars += text.length();
            bounded.add(segment);
        }
        String next = null;
        if ((hasMore || bounded.size() < page.size()) && !bounded.isEmpty()) {
            Segment last = bounded.getLast();
            next = encode(last.getChunkOrder(), last.getSegmentId());
        }
        List<ConversationRetrievalCandidate> evidence = bounded.stream()
                .map(segment -> candidate(segment, asset.getFileName(), asset.getTitle()))
                .toList();
        try {
            return AgentToolResult.success(objectMapper.writeValueAsString(Map.of(
                    "success", true, "assetId", asset.getId(), "fileName", asset.getFileName(),
                    "segments", evidence.stream().map(this::view).toList(),
                    "nextCursor", next == null ? "" : next, "hasMore", next != null)), evidence,
                    Map.of("segmentCount", evidence.size(), "evidenceCount", evidence.size(), "hasMore", next != null));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool result", e);
        }
    }

    private ConversationRetrievalCandidate candidate(Segment segment, String assetFileName, String assetTitle) {
        String sourceRef = StringUtils.hasText(assetFileName)
                ? assetFileName
                : StringUtils.hasText(segment.getSourceRef()) ? segment.getSourceRef() : assetTitle;
        return ConversationRetrievalCandidate.builder().segmentId(segment.getSegmentId()).kbId(segment.getKbId())
                .assetId(segment.getAssetId()).assetType(segment.getAssetType())
                .segmentType(segment.getSegmentType() == null ? null : segment.getSegmentType().name())
                .sourceRef(sourceRef).title(segment.getTitle()).content(content(segment)).pageNo(segment.getPageNo())
                .anchor(ConversationRetrievalCandidate.Anchor.builder().pageNo(segment.getPageNo())
                        .chunkOrder(segment.getChunkOrder()).bbox(segment.getBbox()).build()).build();
    }

    private Map<String, Object> view(ConversationRetrievalCandidate value) {
        return Map.of("segmentId", value.getSegmentId(), "title", value.getTitle() == null ? "" : value.getTitle(),
                "pageNo", value.getPageNo() == null ? -1 : value.getPageNo(), "content", value.getContent());
    }

    private String content(Segment segment) {
        if (StringUtils.hasText(segment.getContentText())) return segment.getContentText();
        if (StringUtils.hasText(segment.getOcrText())) return segment.getOcrText();
        return "";
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
