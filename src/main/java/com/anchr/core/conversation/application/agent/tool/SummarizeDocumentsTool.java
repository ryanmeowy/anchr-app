package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentDeferredTask;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SummarizeDocumentsTool implements AgentTool<SummarizeDocumentsTool.Input> {
    public record Input(
                        @JsonPropertyDescription("优先填写 find_documents 返回的 documents[].assetId；也允许当前授权范围内唯一匹配的完整文件名或标题。不得填写 segmentId。")
                        @NotEmpty @Size(max = 3) List<@NotBlank String> assetIds,
                        @NotBlank @Size(max = 2_000) String instruction,
                        @Size(max = 32) String language) {}

    private final AgentScopeGuard scopeGuard;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "summarize_documents"; }
    @Override public String description() {
        return "异步总结、分析或比较一至三份已明确定位的文档。优先原样复用 find_documents 返回的 documents[].assetId，不能使用 segmentId。仅在用户明确要求时调用。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        List<Map<String, String>> assets = input.assetIds().stream().distinct().map(id -> {
            var asset = scopeGuard.requireAsset(id, context);
            return Map.of("assetId", asset.getId(), "kbId", asset.getKbId(), "fileName",
                    asset.getFileName() == null ? "" : asset.getFileName());
        }).toList();
        if (assets.size() != input.assetIds().stream().distinct().count()) {
            return AgentToolResult.failure("INVALID_ARGUMENTS", "{\"success\":false}");
        }
        String taskId = "agt_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String request = objectMapper.writeValueAsString(Map.of(
                    "assets", assets, "instruction", input.instruction().trim(),
                    "language", input.language() == null ? "" : input.language().trim()));
            String content = objectMapper.writeValueAsString(Map.of(
                    "success", true, "deferred", true, "taskId", taskId,
                    "message", "文档总结任务已创建"));
            return AgentToolResult.deferred(content, new AgentDeferredTask(taskId, "DOCUMENT_SUMMARY", request),
                    Map.of("documentCount", assets.size(), "taskType", "DOCUMENT_SUMMARY"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create summary task", e);
        }
    }
}
