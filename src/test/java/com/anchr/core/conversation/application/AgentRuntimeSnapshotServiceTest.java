package com.anchr.core.conversation.application;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunActivityDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeSnapshotServiceTest {

    @Test
    void ttlCoversAllConfiguredTaskAttempts() {
        AgentRuntimeSnapshotService service = service(
                RuntimeConfigTestUnits.values(Map.of(
                        "AGENT.runtimeSnapshotTtlSeconds", "600",
                        "AGENT.taskTimeoutSeconds", "600",
                        "AGENT.taskMaxRetries", "2")),
                mock(StringRedisTemplate.class));

        assertThat(service.effectiveTtl()).isEqualTo(Duration.ofMinutes(35));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishUsesVersionedSlidingTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("anchr:agent:runtime:snapshot:run-1")).thenReturn(null);
        when(values.increment("anchr:agent:runtime:version:run-1")).thenReturn(1L);

        AgentRunActivityService activityService = mock(AgentRunActivityService.class);
        AgentRunActivityDTO activity = new AgentRunActivityDTO();
        activity.setRunId("run-1");
        activity.setSessionId("session-1");
        activity.setTurnId("turn-1");
        activity.setStatus("RUNNING");
        activity.setSteps(List.of());
        when(activityService.get("run-1")).thenReturn(activity);

        AgentRuntimeSnapshotService service = new AgentRuntimeSnapshotService(
                redis, new ObjectMapper(), activityService,
                mock(ConversationTurnCodec.class), RuntimeConfigTestUnits.defaults());

        service.publishActivity("run-1");

        verify(values).set(eq("anchr:agent:runtime:snapshot:run-1"), any(String.class), eq(Duration.ofMinutes(35)));
        verify(values).set("anchr:agent:runtime:version:run-1", "1", Duration.ofMinutes(35));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishProgressKeepsTransientRunningStepBeforeItIsPersisted() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("anchr:agent:runtime:snapshot:run-1")).thenReturn(null);
        when(values.increment("anchr:agent:runtime:version:run-1")).thenReturn(1L);

        AgentRunActivityService activityService = mock(AgentRunActivityService.class);
        AgentRunActivityDTO activity = new AgentRunActivityDTO();
        activity.setRunId("run-1");
        activity.setSessionId("session-1");
        activity.setStatus("RUNNING");
        activity.setSteps(List.of());
        when(activityService.get("run-1")).thenReturn(activity);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRuntimeSnapshotService service = new AgentRuntimeSnapshotService(
                redis, objectMapper, activityService,
                mock(ConversationTurnCodec.class), RuntimeConfigTestUnits.defaults());

        service.publishProgress(new AgentProgressEvent("run-1", "agent_thinking", "decision_started", 1,
                Map.of("stepOrder", 1, "decision", "ANALYZING")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("anchr:agent:runtime:snapshot:run-1"), json.capture(), eq(Duration.ofMinutes(35)));
        var snapshot = objectMapper.readValue(json.getValue(),
                com.anchr.core.conversation.interfaces.rest.dto.AgentRuntimeSnapshotDTO.class);
        assertThat(snapshot.getActivity().getSteps()).singleElement().satisfies(step -> {
            assertThat(step.getStepOrder()).isEqualTo(1);
            assertThat(step.getStatus()).isEqualTo("RUNNING");
            assertThat(step.getDecision()).isEqualTo("ANALYZING");
        });
    }

    private AgentRuntimeSnapshotService service(
            RuntimeConfigUnit runtimeConfigUnit,
            StringRedisTemplate redis) {
        return new AgentRuntimeSnapshotService(redis, new ObjectMapper(),
                mock(AgentRunActivityService.class),
                mock(ConversationTurnCodec.class), runtimeConfigUnit);
    }
}
