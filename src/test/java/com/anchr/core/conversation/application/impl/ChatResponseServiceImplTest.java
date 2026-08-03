package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatResponseServiceImplTest {

    @Mock
    private ConversationRepository repository;
    @Mock
    private ConversationGenerationPort generationPort;

    @Test
    void shouldGenerateChatReplyWithSystemMessage() {
        ChatResponseServiceImpl service = service();
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any())).thenReturn("你好！有什么想了解的吗？");

        var result = service.generate("session", "你好");

        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(generationPort).generate(messages.capture(), any());
        assertThat(messages.getValue()).extracting(ConversationModelMessage::role)
                .containsExactly("system", "user");
    }

    @Test
    void shouldReturnLocalFallbackWhenModelFails() {
        ChatResponseServiceImpl service = service();
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any())).thenThrow(new IllegalStateException("unavailable"));

        var result = service.generate("session", "你好");

        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.MODEL_FALLBACK);
        assertThat(result.fallbackReason()).isEqualTo("chat_model_unavailable");
        assertThat(result.answer()).isNotBlank();
    }

    private ChatResponseServiceImpl service() {
        ChatResponseServiceImpl service = new ChatResponseServiceImpl(repository, generationPort,
                new SimpleMeterRegistry(), RuntimeConfigTestUnits.defaults());
        return service;
    }
}
