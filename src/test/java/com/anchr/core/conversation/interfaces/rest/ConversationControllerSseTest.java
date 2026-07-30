package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.config.AgentProperties;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerSseTest {

    @Test
    void streamMessage_shouldDisableProxyBufferingAndTransformation() {
        ConversationService service = mock(ConversationService.class);
        ConversationMessageStreamAdapter streamAdapter =
                mock(ConversationMessageStreamAdapter.class);
        ConversationController controller = new ConversationController(
                service, streamAdapter, new AgentProperties());
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("hello");
        SseEmitter emitter = new SseEmitter();
        when(streamAdapter.stream("session-1", request)).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.streamMessage("session-1", request, response)).isSameAs(emitter);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-store, no-transform");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeader("Connection")).isEqualTo("keep-alive");
        verify(streamAdapter).stream("session-1", request);
    }

    @Test
    void getMessage_shouldDelegateExactTurnLookup() {
        ConversationService service = mock(ConversationService.class);
        ConversationController controller = new ConversationController(
                service,
                mock(ConversationMessageStreamAdapter.class),
                new AgentProperties());
        ConversationTurnDTO turn = new ConversationTurnDTO();
        turn.setTurnId("turn-1");
        when(service.getMessage("session-1", "turn-1")).thenReturn(turn);

        assertThat(controller.getMessage("session-1", "turn-1").getData()).isSameAs(turn);
        verify(service).getMessage("session-1", "turn-1");
    }
}
