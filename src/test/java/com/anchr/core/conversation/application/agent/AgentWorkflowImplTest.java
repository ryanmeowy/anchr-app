package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.config.AgentProperties;
import com.anchr.core.conversation.domain.port.AgentModelPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentWorkflowImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void directChat_shouldFinishWithoutKnowledgeToolOrIntent() {
        AgentModelPort model = request -> new AgentModelResponse("你好，我能帮你查找和理解知识库内容。",
                List.of(), AgentTokenUsage.EMPTY, "model", "stop", "req");
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findRecentTurns("session", 10)).thenReturn(List.of());
        AgentWorkflowImpl workflow = workflow(model, List.of(new DeliverOnlyTool()), conversations);

        var result = workflow.execute(run("你好"), ConversationProgressListener.NOOP);

        assertThat(result.executionMode()).isEqualTo(ConversationExecutionMode.AGENT);
        assertThat(result.intent()).isNull();
        assertThat(result.retrievalExecuted()).isFalse();
        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
    }

    @Test
    void knowledgeTool_shouldOnlyAllowRegisteredEvidenceCitation() {
        AtomicInteger calls = new AtomicInteger();
        AgentModelPort model = request -> calls.getAndIncrement() == 0
                ? new AgentModelResponse(null, List.of(new AgentToolCall("call-1", "test_search", "{\"query\":\"权限\"}")),
                    AgentTokenUsage.EMPTY, "model", "tool_calls", "req-1")
                : new AgentModelResponse(null, List.of(new AgentToolCall("call-2", "deliver_answer",
                    "{\"answer\":\"默认关闭 {{segment:seg-1}}\",\"citedSegmentIds\":[\"seg-1\"]}")),
                    AgentTokenUsage.EMPTY, "model", "tool_calls", "req-2");
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findRecentTurns("session", 10)).thenReturn(List.of());
        AgentWorkflowImpl workflow = workflow(model, List.of(new TestSearchTool(), new DeliverOnlyTool()), conversations);

        var result = workflow.execute(run("权限默认值是什么"), ConversationProgressListener.NOOP);

        assertThat(result.retrievalExecuted()).isTrue();
        assertThat(result.answer()).isEqualTo("默认关闭 [1]");
        assertThat(result.citations()).extracting("segmentId").containsExactly("seg-1");
    }

    @Test
    void cancelRun_shouldInterruptModelAndReturnCancelledResult() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        AgentModelPort model = request -> {
            modelStarted.countDown();
            try {
                Thread.sleep(5_000);
                throw new AssertionError("model call should be interrupted");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findRecentTurns("session", 10)).thenReturn(List.of());
        AgentRunCancellationRegistry cancellation = new AgentRunCancellationRegistry();
        AgentWorkflowImpl workflow = workflow(model, List.of(new DeliverOnlyTool()), conversations, cancellation);
        CompletableFuture<ConversationExecutionResult> future = CompletableFuture.supplyAsync(
                () -> workflow.execute(run("分析文档"), ConversationProgressListener.NOOP));
        assertThat(modelStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cancellation.cancel("run-1")).isTrue();
        ConversationExecutionResult result = future.get(2, TimeUnit.SECONDS);

        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.CANCELLED);
        assertThat(result.answer()).isEqualTo("查询已取消。");
    }

    private AgentWorkflowImpl workflow(AgentModelPort model, List<AgentTool<?>> tools,
                                       ConversationRepository conversations) {
        return workflow(model, tools, conversations, new AgentRunCancellationRegistry());
    }

    private AgentWorkflowImpl workflow(AgentModelPort model, List<AgentTool<?>> tools,
                                       ConversationRepository conversations,
                                       AgentRunCancellationRegistry cancellationRegistry) {
        AgentProperties properties = new AgentProperties(); properties.setMaxSteps(6); properties.setMaxToolCalls(4);
        AgentToolRegistry registry = new AgentToolRegistry(tools);
        AgentToolExecutor executor = new AgentToolExecutor(registry, objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator());
        return new AgentWorkflowImpl(properties, model, registry, executor, conversations,
                new ConversationCitationMapper(), mock(AgentTraceRecorder.class),
                cancellationRegistry, objectMapper,
                new SimpleMeterRegistry());
    }

    private AgentRunRequest run(String query) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO(); request.setQuery(query);
        request.setKbIds(List.of("kb-1")); request.setAssetIdList(List.of());
        return new AgentRunRequest("run-1", "turn-1", "session", "single_user", request);
    }

    record SearchInput(@NotBlank String query) {}
    static class TestSearchTool implements AgentTool<SearchInput> {
        public String name(){return "test_search";} public String description(){return "test";}
        public Class<SearchInput> inputType(){return SearchInput.class;}
        public AgentToolResult execute(SearchInput input, AgentExecutionContext context){
            var evidence=ConversationRetrievalCandidate.builder().segmentId("seg-1").kbId("kb-1")
                    .assetId("asset-1").sourceRef("manual.pdf").content("权限默认关闭").build();
            return AgentToolResult.success("{\"segmentId\":\"seg-1\"}",List.of(evidence));
        }
    }
    static class DeliverOnlyTool extends com.anchr.core.conversation.application.agent.tool.DeliverAnswerTool {}
}
