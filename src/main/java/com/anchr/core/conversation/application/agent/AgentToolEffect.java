package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.impl.EvidenceCleaningService;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.EvidenceCheckReport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_FINALIZER_EVIDENCE;
import static com.anchr.core.conversation.application.constant.AgentConstant.FIND_DOCUMENTS_SNIPPET_MAX_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_MODEL_TOOL_RESULT_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_COMPACT_FIELD_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_EVIDENCE_CONTENT_CHARS;

@Component
class AgentToolEffect {
    private final AgentToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private final EvidenceCleaningService cleaner;

    AgentToolEffect(AgentToolExecutor toolExecutor, ObjectMapper objectMapper,
                    EvidenceCleaningService cleaner) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.cleaner = cleaner;
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
            if (Set.of("search_knowledge", "find_documents", "read_document").contains(command.call().name())) {
                result = inspect(command.call().name(), result, state.budget());
            }
            String modelMessage = compactToolResult(result);
            result = countModelSegments(result, modelMessage);
            long ended = System.currentTimeMillis();
            return new AgentEvent.ToolCompleted(command.call(), result, modelMessage,
                    command.attempt(), ended - started, ended);
        } catch (RuntimeException e) {
            long ended = System.currentTimeMillis();
            return new AgentEvent.ToolFailed(command.call(), e, command.attempt(), ended - started, ended);
        }
    }

    private AgentToolResult inspect(String tool, AgentToolResult raw, AgentBudget budget) {
        if (!raw.success()) return AgentToolResult.failure(raw.errorCode(), "{\"success\":false,\"message\":\"资料读取失败\"}");
        EvidenceCheckReport report = null;
        try {
            ObjectNode toolContent = (ObjectNode) objectMapper.readTree(raw.content());
            // Derived text is rebuilt from the canonical evidence after inspection.
            if (tool.equals("search_knowledge")) toolContent.remove("evidence");
            if (tool.equals("read_document")) toolContent.remove("segments");
            if (tool.equals("find_documents")) for (var document : toolContent.path("documents"))
                ((ObjectNode) document).remove("matchSnippet");
            var input = objectMapper.createObjectNode();
            input.set("toolContent", toolContent);
            var candidates = objectMapper.valueToTree(raw.evidence());
            for (var candidate : candidates) if (!candidate.path("content").asText().isBlank())
                ((ObjectNode) candidate).remove("snippet");
            input.set("evidence", candidates);
            var checked = cleaner.clean(input, false, budget.boundedTimeout(Duration.ofSeconds(60), System.currentTimeMillis()));
            report = checked.report();
            if (!checked.success()) return failedCheck(report, raw.evidence().size());
            var evidence = EvidenceCleaningService.candidates(objectMapper, raw.evidence(), checked.cleaned().path("evidence"));
            ObjectNode safeContent = (ObjectNode) checked.cleaned().path("toolContent");
            Map<String, ConversationRetrievalCandidate> byId = new LinkedHashMap<>();
            evidence.forEach(candidate -> byId.put(candidate.getSegmentId(), candidate));
            int returnedSegments = 0;
            int documents = 0;
            switch (tool) {
                case "search_knowledge", "read_document" -> {
                    var segments = objectMapper.createArrayNode();
                    for (var candidate : evidence) {
                        var view = objectMapper.createObjectNode();
                        view.put("segmentId", candidate.getSegmentId());
                        if (tool.equals("search_knowledge")) view.put("assetId", candidate.getAssetId());
                        view.put("title", safe(candidate.getTitle()));
                        view.put("pageNo", candidate.getPageNo() == null ? -1 : candidate.getPageNo());
                        view.put("content", candidate.getContent());
                        segments.add(view);
                    }
                    safeContent.set(tool.equals("search_knowledge") ? "evidence" : "segments", segments);
                    returnedSegments = evidence.size();
                    if (tool.equals("read_document")) documents = 1;
                }
                case "find_documents" -> {
                    for (var node : safeContent.path("documents")) {
                        ObjectNode document = (ObjectNode) node;
                        var source = byId.get(document.path("matchedSegmentId").asText());
                        document.put("matchedSegmentId", source == null ? "" : source.getSegmentId());
                        document.put("matchSnippet", source == null ? "" : clip(source.getContent(), FIND_DOCUMENTS_SNIPPET_MAX_CHARS));
                        if (source != null) returnedSegments++;
                        documents++;
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported evidence tool");
            }
            if (!raw.evidence().isEmpty() && evidence.isEmpty() && !tool.equals("find_documents"))
                return failedCheck(report, raw.evidence().size());
            EvidenceStatistics statistics = new EvidenceStatistics(raw.evidence().size(), evidence.size(), returnedSegments, documents);
            // Source tool counts are superseded by the inspected result, not copied into model input.
            Map<String, Object> details = new LinkedHashMap<>(raw.traceDetails());
            details.keySet().removeAll(Set.of("evidenceCheck", "originalEvidenceCount", "evidenceCount", "segmentCount", "documentCount"));
            return new AgentToolResult(true, objectMapper.writeValueAsString(safeContent), evidence,
                    null, null, null, details, report, statistics);
        } catch (Exception ignored) {
            return failedCheck(report == null ? EvidenceCheckReport.failed("inspection_processing_failed") : report, raw.evidence().size());
        }
    }

    private AgentToolResult failedCheck(EvidenceCheckReport report, int originalCount) {
        return new AgentToolResult(false, "{\"success\":false,\"errorCode\":\"EVIDENCE_CHECK_FAILED\",\"message\":\"本批资料未通过证据检查，不得使用；仅可使用此前已检查证据\"}",
                List.of(), null, null, "EVIDENCE_CHECK_FAILED", Map.of(), report,
                new EvidenceStatistics(originalCount, 0, 0, 0));
    }

    private AgentToolResult countModelSegments(AgentToolResult result, String modelMessage) {
        if (result.evidenceStatistics() == null) return result;
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode payload = objectMapper.readTree(modelMessage);
            for (String key : List.of("evidence", "segments")) for (var segment : payload.path(key)) {
                String id = segment.path("segmentId").asText();
                if (!id.isBlank()) ids.add(id);
            }
            for (var document : payload.path("documents")) {
                String id = document.path("matchedSegmentId").asText();
                if (!id.isBlank()) ids.add(id);
            }
        } catch (Exception ignored) {
            // A failed compaction payload contains no usable segment bodies.
        }
        var counts = result.evidenceStatistics();
        return new AgentToolResult(result.success(), result.content(), result.evidence(), result.deferredTask(),
                result.finalAnswer(), result.errorCode(), result.traceDetails(), result.evidenceCheck(),
                new EvidenceStatistics(counts.originalEvidenceCount(), counts.evidenceCount(), ids.size(), counts.documentCount()));
    }

    private String compactToolResult(AgentToolResult result) {
        if (result.content().length() <= MAX_MODEL_TOOL_RESULT_CHARS) return result.content();
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("success", result.success());
        if (StringUtils.hasText(result.errorCode())) compact.put("errorCode", result.errorCode());
        compact.put("truncatedForPlanning", true);
        result.traceDetails().forEach((key, value) -> {
            if (Set.of("hasMore", "documentCount", "segmentCount", "evidenceCount").contains(key)
                    && (value instanceof Number || value instanceof Boolean)) compact.put(key, value);
        });
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
