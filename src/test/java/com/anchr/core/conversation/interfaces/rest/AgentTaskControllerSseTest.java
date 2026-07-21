package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.conversation.application.AgentTaskQueryService;
import com.anchr.core.conversation.application.agent.AgentTaskStreamService;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskControllerSseTest {

    @Test
    void stream_shouldValidateTaskAndDisableProxyBuffering() {
        AgentTaskQueryService queryService = mock(AgentTaskQueryService.class);
        AgentTaskStreamService streamService = mock(AgentTaskStreamService.class);
        AgentTaskController controller = new AgentTaskController(queryService, streamService);
        AgentTaskDTO task = new AgentTaskDTO();
        task.setTaskId("task-1");
        SseEmitter emitter = new SseEmitter();
        when(queryService.get("task-1")).thenReturn(task);
        when(streamService.subscribe(task)).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.stream("task-1", response)).isSameAs(emitter);

        verify(queryService).get("task-1");
        verify(streamService).subscribe(task);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-store, no-transform");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeader("Connection")).isEqualTo("keep-alive");
    }
}
