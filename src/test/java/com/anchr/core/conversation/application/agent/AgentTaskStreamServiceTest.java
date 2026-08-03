package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AnswerIdentity;
import com.anchr.core.conversation.application.LocalAnswerEventBroker;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskStreamServiceTest {

    @Test
    void adaptsSharedAnswerEventsToCompatibleTaskSseEvents() {
        LocalAnswerEventBroker broker = new LocalAnswerEventBroker();
        AgentTaskStreamService service = new AgentTaskStreamService(
                broker, new ConversationTurnCodec(new ObjectMapper()));
        AgentTaskDTO task = runningTask();
        SseEmitter emitter = service.subscribe(task);
        AnswerIdentity identity = new AnswerIdentity(
                "task-1", "turn-1", "session-1", "task-1", "run-1", 1);

        broker.started(identity);
        broker.progress(identity, "FINALIZING", 90);
        broker.delta(identity, "流式");
        broker.snapshot(identity, "最终回答");
        broker.citations(identity, List.of());
        broker.completed(identity);

        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> events =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(
                        emitter, "earlySendAttempts");
        assertThat(events).isNotNull();
        String framing = events.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining());
        assertThat(framing).contains(
                "event:task", "event:delta", "event:answer_reset", "event:citations", "event:done");
        Map<?, ?> deltaValue = events.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(value -> "流式".equals(value.get("text")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) deltaValue;
        assertThat(delta).containsEntry("answerId", "turn-1").containsEntry("revision", 1L);
        assertThat((Long) delta.get("sequence")).isPositive();
    }

    private AgentTaskDTO runningTask() {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setTurnId("turn-1");
        task.setRunId("run-1");
        task.setRevision(1);
        task.setType("DOCUMENT_SUMMARY");
        task.setStatus("RUNNING");
        task.setProgress(80);
        task.setCurrentStage("REDUCE_SUMMARY");
        return task;
    }
}
