package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentAnswerType;
import com.anchr.core.conversation.application.agent.AgentFinalAnswer;
import com.anchr.core.conversation.application.agent.AgentTool;
import com.anchr.core.conversation.application.agent.AgentToolResult;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.anchr.core.conversation.application.constant.AgentConstant.DELIVER_ANSWER_MAX_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.DELIVER_ANSWER_MAX_CITATIONS;

@Component
public class DeliverAnswerTool implements AgentTool<DeliverAnswerTool.Input> {
    public record Input(
            @NotNull
            @JsonPropertyDescription("回答类型：普通聊天用 CHAT，需要用户补充必要条件用 CLARIFICATION，证据直接支持核心答案时用 KNOWLEDGE，知识工具已检索但证据无法支持核心答案时用 NO_EVIDENCE。KNOWLEDGE 必须引用本轮证据；NO_EVIDENCE 禁止引用。")
            AgentAnswerType answerType,
            @NotBlank
            @Size(max = DELIVER_ANSWER_MAX_CHARS)
            @JsonPropertyDescription("面向用户的 Markdown 回答。引用事实使用 {{segment:实际ID}} 内部标记；每个结论优先一个最直接证据、确需交叉验证时最多两个，每段最多三个、全文通常不超过十个不同引用；不得解释或直接展示 segmentId，也不得自行生成 [数字] 引用。")
            String answer,
            @Size(max = DELIVER_ANSWER_MAX_CITATIONS)
            @JsonPropertyDescription("支持回答的内部证据 Segment ID，仅填写回答正文 Marker 实际使用且由本轮知识工具返回的值。")
            List<String> citedSegmentIds) {
    }

    @Override public String name() { return "deliver_answer"; }
    @Override public String description() {
        return "提交最终回答并声明 CHAT、CLARIFICATION、KNOWLEDGE 或 NO_EVIDENCE。"
                + "KNOWLEDGE 必须使用本轮直接证据；NO_EVIDENCE 必须为空引用；Segment ID 仅用于内部验真。";
    }
    @Override public Class<Input> inputType() { return Input.class; }

    @Override
    public AgentToolResult execute(Input input, AgentExecutionContext context) {
        AgentFinalAnswer answer = new AgentFinalAnswer(
                input.answerType(), input.answer().trim(),
                input.citedSegmentIds() == null ? List.of() : input.citedSegmentIds());
        return AgentToolResult.finalAnswer(answer, java.util.Map.of(
                "answerType", input.answerType().name(),
                "citationCount", answer.citedSegmentIds().size()));
    }
}
