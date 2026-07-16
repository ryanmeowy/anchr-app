package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentFinalAnswer;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeliverAnswerTool implements AgentTool<DeliverAnswerTool.Input> {
    public record Input(
            @NotBlank
            @Size(max = 20_000)
            @JsonPropertyDescription("面向用户的 Markdown 回答。引用事实使用 {{segment:实际ID}} 内部标记；不得解释或直接展示 segmentId，也不得自行生成 [数字] 引用。")
            String answer,
            @Size(max = 20)
            @JsonPropertyDescription("支持回答的内部证据 Segment ID，仅填写本轮知识工具实际返回的值。后端会按 Asset 聚合为用户可见引用。")
            List<String> citedSegmentIds) {
    }

    @Override public String name() { return "deliver_answer"; }
    @Override public String description() {
        return "提交最终回答。Segment ID 仅用于内部验真，回答中的引用会由后端按 Asset 聚合并生成 [数字]；普通聊天引用留空。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        return AgentToolResult.finalAnswer(new AgentFinalAnswer(
                input.answer().trim(), input.citedSegmentIds() == null ? List.of() : input.citedSegmentIds()));
    }
}
