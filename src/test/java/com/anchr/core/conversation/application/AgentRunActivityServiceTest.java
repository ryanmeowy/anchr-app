package com.anchr.core.conversation.application;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunActivityServiceTest {

    @Test
    void get_shouldSortStepsAndExposeOnlySafeActivityMetadata() throws Exception {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, mapper);
        AgentRun run = run("COMPLETED");
        when(traces.findRun("run-1")).thenReturn(Optional.of(run));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session("user-a")));
        when(traces.findSteps("run-1")).thenReturn(List.of(
                step(2, "TOOL_RESULT", "{\"tool\":\"search_knowledge\",\"callId\":\"call-1\",\"query\":\"secret\"}",
                        "{\"tool\":\"search_knowledge\",\"callId\":\"call-1\",\"evidenceCount\":8,\"segmentId\":\"seg-secret\",\"documentText\":\"secret body\"}"),
                step(1, "MODEL_DECISION", "{\"prompt\":\"secret prompt\",\"messageCount\":7}",
                        "{\"toolCallCount\":1,\"hasContent\":false}")));

        var result = service.get("run-1");

        assertThat(result.getSessionId()).isEqualTo("session-1");
        assertThat(result.getTurnId()).isEqualTo("turn-1");
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getStepCount()).isEqualTo(3);
        assertThat(result.getSteps()).extracting(step -> step.getStepOrder()).containsExactly(1, 2, 3);
        assertThat(result.getSteps().getFirst().getMessageCount()).isEqualTo(7);
        assertThat(result.getSteps().getFirst().getPlannedToolCallCount()).isEqualTo(1);
        assertThat(result.getSteps().getFirst().getDecision()).isEqualTo("TOOL_SELECTION");
        assertThat(result.getLatencyMs()).isEqualTo(500L);
        assertThat(result.getSteps().get(1).getToolName()).isEqualTo("search_knowledge");
        assertThat(result.getSteps().get(1).getCallId()).isEqualTo("call-1");
        assertThat(result.getSteps().get(1).getEvidenceCount()).isEqualTo(8);
        String json = mapper.writeValueAsString(result);
        assertThat(json).doesNotContain("secret prompt", "secret body", "seg-secret", "query", "documentText");
    }

    @Test
    void get_shouldHideRunWithoutOwningSession() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        when(traces.findRun("run-1")).thenReturn(Optional.of(run("RUNNING")));
        when(conversations.findSession("session-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("run-1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void get_shouldExposeStepUsageAndNormalizeCompletedTaskStageProgress() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        AgentRun run = run("WAITING_TASK");
        AgentStep stage = step(101, "TASK_STAGE", "{}",
                "{\"taskStage\":\"MAP_SUMMARY\",\"progress\":70,"
                        + "\"modelCallCount\":3,\"modelLatencyMs\":61500,"
                        + "\"firstTokenMs\":2300,\"streaming\":true}");
        when(traces.findRun("run-1")).thenReturn(Optional.of(run));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session("user-a")));
        when(traces.findSteps("run-1")).thenReturn(List.of(stage));

        var result = service.get("run-1");

        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().getFirst().getProgress()).isEqualTo(100);
        assertThat(result.getSteps().getFirst().getPromptTokens()).isEqualTo(1_010);
        assertThat(result.getSteps().getFirst().getCompletionTokens()).isEqualTo(505);
        assertThat(result.getSteps().getFirst().getModelCallCount()).isEqualTo(3);
        assertThat(result.getSteps().getFirst().getModelLatencyMs()).isEqualTo(61_500L);
        assertThat(result.getSteps().getFirst().getFirstTokenMs()).isEqualTo(2_300L);
        assertThat(result.getSteps().getFirst().getStreaming()).isTrue();
    }

    @Test
    void get_shouldDistinguishAgentDegradedFromTraditionalFallback() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        AgentRun run = run("DEGRADED");
        when(traces.findRun("run-1")).thenReturn(Optional.of(run));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session("user-a")));
        when(traces.findSteps("run-1")).thenReturn(List.of());

        var result = service.get("run-1");

        assertThat(result.getStatus()).isEqualTo("AGENT_DEGRADED");
    }

    @Test
    void get_shouldNotAppendSyntheticFinalWhenPersistedFinalExists() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        AgentRun run = run("COMPLETED");
        when(traces.findRun("run-1")).thenReturn(Optional.of(run));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session("user-a")));
        when(traces.findSteps("run-1")).thenReturn(List.of(
                step(1, "MODEL_DECISION", "{}", "{\"hasContent\":true}"),
                step(2, "FINAL_ANSWER", "{}", "{\"streaming\":true}")));

        var result = service.get("run-1");

        assertThat(result.getStepCount()).isEqualTo(2);
        assertThat(result.getSteps()).extracting(step -> step.getStepOrder()).containsExactly(1, 2);
        assertThat(result.getSteps()).extracting(step -> step.getType())
                .containsExactly("MODEL_DECISION", "FINAL");
    }

    @Test
    void get_shouldExposeReadLimitGuardAsCompletedDecision() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        AgentRun run = run("RUNNING");
        AgentStep guard = step(6, "TOOL_RESULT",
                "{\"tool\":\"read_document\",\"callId\":\"call-3\"}",
                "{\"tool\":\"read_document\",\"callId\":\"call-3\","
                        + "\"decision\":\"READ_LIMIT_REACHED\",\"evidenceCount\":40}");
        when(traces.findRun("run-1")).thenReturn(Optional.of(run));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session("user-a")));
        when(traces.findSteps("run-1")).thenReturn(List.of(guard));

        var result = service.get("run-1");

        assertThat(result.getSteps()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo("COMPLETED");
            assertThat(step.getDecision()).isEqualTo("READ_LIMIT_REACHED");
            assertThat(step.getErrorCode()).isNull();
        });
    }

    @Test
    void listRecoverable_shouldExposeStableNavigationIdentityWithoutLoadingSteps() {
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentRunActivityService service = new AgentRunActivityService(traces, conversations, new ObjectMapper());
        AgentRun run = run("RUNNING");
        run.setCurrentStep("MODEL_DECISION");
        when(traces.findRecoverableRuns("single_user", 20)).thenReturn(List.of(run));

        var result = service.listRecoverable(99);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getRunId()).isEqualTo("run-1");
            assertThat(item.getSessionId()).isEqualTo("session-1");
            assertThat(item.getTurnId()).isEqualTo("turn-1");
            assertThat(item.getStatus()).isEqualTo("RUNNING");
            assertThat(item.getCurrentStep()).isEqualTo("MODEL_DECISION");
        });
    }

    private AgentRun run(String status) {
        AgentRun run = new AgentRun();
        run.setRunId("run-1");
        run.setSessionId("session-1");
        run.setTurnId("turn-1");
        run.setWorkflowVersion("general-agent-v1");
        run.setStatus(status);
        run.setStepCount(2);
        run.setToolCallCount(1);
        run.setPromptTokens(120);
        run.setCompletionTokens(40);
        run.setStartedAt(1_000L);
        run.setFinishedAt("RUNNING".equals(status) ? null : 1_500L);
        run.setLatencyMs(500L);
        return run;
    }

    private AgentStep step(int order, String type, String input, String output) {
        AgentStep step = new AgentStep();
        step.setStepOrder(order);
        step.setStepType(type);
        step.setStatus("COMPLETED");
        step.setAttempt(1);
        step.setInputSummaryJson(input);
        step.setOutputSummaryJson(output);
        step.setLatencyMs(order * 100L);
        step.setPromptTokens(order * 10);
        step.setCompletionTokens(order * 5);
        step.setCreatedAt(1_000L + order);
        return step;
    }

    private ConversationSession session(String userId) {
        ConversationSession session = new ConversationSession();
        session.setSessionId("session-1");
        session.setUserId(userId);
        return session;
    }

}
