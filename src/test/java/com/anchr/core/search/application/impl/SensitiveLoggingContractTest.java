package com.anchr.core.search.application.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.anchr.core.conversation.application.impl.QueryRewriteServiceImpl;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveLoggingContractTest {

    private static final String SENSITIVE_QUERY = "207B_PRIVATE_QUERY_7f3a1d";

    @Test
    void retrievalSuccessShouldLogCountsButNotTheRawQuery() {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        when(knowledgeAcl.resolveVisibleKbIds(List.of("kb-1"))).thenReturn(List.of("kb-1"));
        when(embedding.embedQuery(SENSITIVE_QUERY)).thenReturn(List.of(0.1F));
        when(repository.textSearch(
                anyString(), anyList(), anyInt(), any(SearchFilter.class)))
                .thenReturn(List.of());
        when(repository.vectorSearch(
                anyList(), anyInt(), anyFloat(), any(SearchFilter.class)))
                .thenReturn(List.of());
        RetrievalQueryServiceImpl service = RetrievalQueryServiceTestFactory.create(
                repository,
                embedding,
                knowledgeAcl,
                rerankPort,
                RuntimeConfigTestUnits.defaults(),
                new SimpleMeterRegistry());

        try (LogCapture logs = LogCapture.start(RetrievalQueryServiceImpl.class)) {
            service.query(new RetrievalTopNQuery(
                    SENSITIVE_QUERY,
                    List.of(),
                    5,
                    List.of("kb-1"),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null));

            assertSafe(logs);
            assertThat(logs.messages())
                    .anyMatch(message -> message.contains("queryLength=")
                            && message.contains("recallTopK=")
                            && message.contains("latencyMs="));
        }
    }

    @Test
    void searchRewriteFailureShouldNotLogQueryOrProviderErrorBody() {
        SearchGenerationPort generationPort = prompt -> {
            throw new IllegalStateException("provider echoed " + SENSITIVE_QUERY);
        };
        SearchQueryRewriteServiceImpl service = searchRewriteService(generationPort);

        try (LogCapture logs = LogCapture.start(SearchQueryRewriteServiceImpl.class)) {
            SearchRewriteResult result = service.rewrite(SENSITIVE_QUERY);

            assertThat(result.isFallbackUsed()).isTrue();
            assertSafe(logs);
            assertThat(logs.messages())
                    .anyMatch(message -> message.contains("queryLength=")
                            && message.contains("errorType=IllegalStateException"));
        }
    }

    @Test
    void invalidSearchRewriteOutputShouldLogOnlyLengthAndErrorType() {
        SearchQueryRewriteServiceImpl service = searchRewriteService(
                prompt -> "{\"rewrittenQuery\":\"" + SENSITIVE_QUERY + "\",broken}");

        try (LogCapture logs = LogCapture.start(SearchQueryRewriteServiceImpl.class)) {
            SearchRewriteResult result = service.rewrite(SENSITIVE_QUERY);

            assertThat(result.isFallbackUsed()).isTrue();
            assertSafe(logs);
            assertThat(logs.messages())
                    .anyMatch(message -> message.contains("rawTextLength=")
                            && message.contains("errorType="));
        }
    }

    @Test
    void followUpFailureShouldNotLogQuerySnippetOrProviderErrorBody() {
        SearchFollowUpServiceImpl service = new SearchFollowUpServiceImpl(
                prompt -> {
                    throw new IllegalStateException("provider echoed " + SENSITIVE_QUERY);
                },
                new ObjectMapper(),
                new SimpleMeterRegistry());
        RetrievalHit hit = new RetrievalHit(
                SegmentType.TEXT_CHUNK.name(),
                null,
                null,
                null,
                null,
                "document snippet " + SENSITIVE_QUERY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);

        try (LogCapture logs = LogCapture.start(SearchFollowUpServiceImpl.class)) {
            assertThat(service.generate(SENSITIVE_QUERY, List.of(hit))).isEmpty();

            assertSafe(logs);
            assertThat(logs.messages())
                    .anyMatch(message -> message.contains("queryLength=")
                            && message.contains("resultCount=1")
                            && message.contains("errorType=IllegalStateException"));
        }
    }

    @Test
    void invalidConversationRewriteOutputShouldNotBeLogged() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ConversationGenerationPort generationPort = mock(ConversationGenerationPort.class);
        when(repository.findRecentTurns("session-1", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any()))
                .thenReturn("{\"rewrittenQuery\":\"" + SENSITIVE_QUERY + "\",broken}");
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                repository,
                generationPort,
                new ObjectMapper(),
                new SimpleMeterRegistry());

        try (LogCapture logs = LogCapture.start(QueryRewriteServiceImpl.class)) {
            assertThat(service.rewrite("session-1", SENSITIVE_QUERY).isFallbackUsed()).isTrue();

            assertSafe(logs);
            assertThat(logs.messages())
                    .anyMatch(message -> message.contains("rawTextLength=")
                            && message.contains("errorType="));
        }
    }

    private SearchQueryRewriteServiceImpl searchRewriteService(
            SearchGenerationPort generationPort
    ) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        return new SearchQueryRewriteServiceImpl(
                generationPort,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                redisTemplate);
    }

    private void assertSafe(LogCapture logs) {
        assertThat(logs.messages()).noneMatch(message -> message.contains(SENSITIVE_QUERY));
        assertThat(logs.throwableMessages()).noneMatch(message -> message.contains(SENSITIVE_QUERY));
    }

    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.appender = appender;
        }

        private static LogCapture start(Class<?> type) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private List<String> messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        private List<String> throwableMessages() {
            List<String> messages = new ArrayList<>();
            for (ILoggingEvent event : appender.list) {
                collectThrowableMessages(event.getThrowableProxy(), messages);
            }
            return List.copyOf(messages);
        }

        private void collectThrowableMessages(
                IThrowableProxy throwable,
                List<String> messages
        ) {
            if (throwable == null) {
                return;
            }
            if (throwable.getMessage() != null) {
                messages.add(throwable.getMessage());
            }
            collectThrowableMessages(throwable.getCause(), messages);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
