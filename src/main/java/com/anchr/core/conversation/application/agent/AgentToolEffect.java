package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_FINALIZER_EVIDENCE;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_MODEL_TOOL_RESULT_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_COMPACT_FIELD_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_EVIDENCE_CONTENT_CHARS;

@Component
class AgentToolEffect {
    private final AgentToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    AgentToolEffect(AgentToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    AgentEvent execute(AgentState state, AgentCommand.CallTool command) {
        long started = System.currentTimeMillis();
        try {
            AgentRunRequest request = state.runRequest();
            AgentExecutionContext context = new AgentExecutionContext(request.runId(), request.turnId(),
                    request.sessionId(), request.userId(), request.request().getKbIds(),
                    request.request().getAssetIdList(), state.budget());
            AgentToolResult result = toolExecutor.execute(
                    command.call().name(), command.call().arguments(), context);
            long ended = System.currentTimeMillis();
            return new AgentEvent.ToolCompleted(command.call(), result, compactToolResult(result),
                    command.attempt(), ended - started, ended);
        } catch (RuntimeException e) {
            long ended = System.currentTimeMillis();
            return new AgentEvent.ToolFailed(command.call(), e, command.attempt(), ended - started, ended);
        }
    }

    private String compactToolResult(AgentToolResult result) {
        if (result.content().length() <= MAX_MODEL_TOOL_RESULT_CHARS) return result.content();
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("success", result.success());
        if (StringUtils.hasText(result.errorCode())) compact.put("errorCode", result.errorCode());
        compact.put("truncatedForPlanning", true);
        compact.putAll(result.traceDetails());
        copyScalarFields(result.content(), compact);
        List<Map<String, Object>> evidence = result.evidence().stream()
                .limit(MAX_FINALIZER_EVIDENCE).map(this::modelEvidenceView).toList();
        if (!evidence.isEmpty()) compact.put("evidence", evidence);
        try {
            return objectMapper.writeValueAsString(compact);
        } catch (Exception e) {
            return "{\"success\":false,\"errorCode\":\"TOOL_RESULT_COMPACTION_FAILED\"}";
        }
    }

    private void copyScalarFields(String content, Map<String, Object> compact) {
        try {
            JsonNode root = objectMapper.readTree(content);
            for (String field : List.of("assetId", "fileName", "rewrittenQuery", "nextCursor", "hasMore")) {
                JsonNode value = root.get(field);
                if (value != null && !value.isContainerNode()) {
                    compact.put(field, value.isTextual()
                            ? clip(value.asText(), PLANNING_COMPACT_FIELD_CHARS) : value);
                }
            }
            JsonNode documents = root.get("documents");
            if (documents != null && documents.isArray()) compact.put("documents", documents);
        } catch (Exception ignored) {
            // The evidence view remains a valid compact result when the original body is malformed.
        }
    }

    private Map<String, Object> modelEvidenceView(ConversationRetrievalCandidate candidate) {
        return Map.of("segmentId", safe(candidate.getSegmentId()),
                "assetId", safe(candidate.getAssetId()), "title", safe(candidate.getTitle()),
                "pageNo", candidate.getPageNo() == null ? -1 : candidate.getPageNo(),
                "content", clip(evidenceContent(candidate), PLANNING_EVIDENCE_CONTENT_CHARS));
    }

    private String evidenceContent(ConversationRetrievalCandidate candidate) {
        if (candidate == null) return "";
        if (StringUtils.hasText(candidate.getContent())) return candidate.getContent().trim();
        return StringUtils.hasText(candidate.getSnippet()) ? candidate.getSnippet().trim() : "";
    }

    private static String clip(String value, int limit) {
        if (!StringUtils.hasText(value)) return "";
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
