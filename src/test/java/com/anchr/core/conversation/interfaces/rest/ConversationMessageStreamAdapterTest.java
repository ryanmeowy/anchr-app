package com.anchr.core.conversation.interfaces.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.AnswerEventBroker;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.impl.ConversationMessageUseCase;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationMessageStreamAdapterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ConversationMessageStreamAdapter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ConversationMessageUseCase useCase = mock(ConversationMessageUseCase.class);
    private final AnswerEventBroker broker = mock(AnswerEventBroker.class);
    private final AnswerEventBroker.Subscription subscription = mock(AnswerEventBroker.Subscription.class);
    private final ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
    private Level previousLevel;
    private ConversationMessageStreamAdapter adapter;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        when(broker.subscribe(anyString(), any())).thenReturn(subscription);
        adapter = new ConversationMessageStreamAdapter(useCase, Runnable::run,
                mock(AgentRuntimeSnapshotService.class), broker, mock(ConversationTurnCodec.class));
        request.setAgentEnabled(false);
        request.setAnswerMode("STRICT");
        request.setQuery("private question that should not be logged");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void unexpectedRagFailureLogsOriginalStackAndConversationIdentity() {
        RuntimeException failure = new IllegalStateException("retrieval failed");
        failAfterExecutionStarted(failure);

        adapter.stream("session-1", request);

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(failure);
        assertThat(event.getFormattedMessage()).contains("sessionId=session-1", "turnId=turn-1",
                "agentEnabled=false", "answerMode=STRICT", "errorCode=INTERNAL_ERROR", "channelId=")
                .doesNotContain(request.getQuery());
        verify(broker).failed(argThat(identity -> "turn-1".equals(identity.answerId())), eq("INTERNAL_ERROR"));
        verify(subscription).close();
    }

    @Test
    void serverBusinessFailureLogsCause() {
        BusinessException failure = new BusinessException(ApiError.INTERNAL_ERROR,
                new IllegalStateException("upstream failed"));
        failAfterExecutionStarted(failure);

        adapter.stream("session-1", request);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(((ThrowableProxy) logs.list.getFirst().getThrowableProxy()).getThrowable())
                .isSameAs(failure).hasCause(failure.getCause());
        verify(broker).failed(any(), eq("INTERNAL_ERROR"));
    }

    @Test
    void clientBusinessFailureLogsWarningWithoutStack() {
        failAfterExecutionStarted(new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));

        adapter.stream("session-1", request);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        verify(broker).failed(any(), eq("CONVERSATION_SESSION_NOT_FOUND"));
    }

    @Test
    void clientDisconnectDoesNotLogServerFailure() {
        failAfterExecutionStarted(new RuntimeException("ResponseBodyEmitter has already completed"));

        adapter.stream("session-1", request);

        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        verify(broker, never()).failed(any(), anyString());
        verify(subscription).close();
    }

    private void failAfterExecutionStarted(RuntimeException failure) {
        when(useCase.execute(eq("session-1"), same(request), any())).thenAnswer(invocation -> {
            ConversationProgressListener listener = invocation.getArgument(2);
            listener.onExecutionStarted("turn-1", null);
            throw failure;
        });
    }
}
