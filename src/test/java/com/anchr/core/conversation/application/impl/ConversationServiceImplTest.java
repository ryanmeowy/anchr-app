package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ChatResponseService;
import com.anchr.core.conversation.application.agent.AgentDeferredTask;
import com.anchr.core.conversation.application.agent.AgentConversationCleanupService;
import com.anchr.core.conversation.application.ConversationIntentRouter;
import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.QueryRewriteService;
import com.anchr.core.search.application.CitationReasonGenerationService;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ChatResponseResult;
import com.anchr.core.conversation.application.model.ConversationExecutionMode;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationIntentSource;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationTurnPosition;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.anchr.core.search.application.KbScopeResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    @Mock
    private AnswerGenerationService answerGenerationService;
    @Mock
    private KbScopeResolver kbScopeResolver;
    @Mock
    private ActivityEventService activityEventService;
    @Mock
    private ConversationIntentRouter conversationIntentRouter;
    @Mock
    private ChatResponseService chatResponseService;
    @Mock
    private CitationReasonGenerationService citationReasonGenerationService;
    @Mock
    private AgentConversationCleanupService agentConversationCleanupService;
    @Mock
    private AgentTaskRepository agentTaskRepository;

    private InMemoryConversationRepository repository;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConversationRepository();
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        ConversationTurnCodec conversationTurnCodec = new ConversationTurnCodec(objectMapper);
        ConversationMessagePipeline conversationMessagePipeline = new ConversationMessagePipeline(
                queryRewriteService,
                conversationRetrievalOrchestrator,
                new ConversationCitationMapper(),
                new ConversationResultCardMapper(),
                answerGenerationService,
                conversationTurnCodec,
                citationReasonGenerationService
        );
        lenient().when(conversationIntentRouter.route(any(), any())).thenReturn(new ConversationIntentResult(
                ConversationIntentType.KB_QUERY, 1.0D, "test", ConversationIntentSource.MODEL, false));
        ConversationMessageOrchestrator orchestrator = new ConversationMessageOrchestrator(
                conversationIntentRouter,
                chatResponseService,
                conversationMessagePipeline,
                meterRegistry
        );
        when(kbScopeResolver.resolveVisibleKbIds(any())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(0);
            return requested == null ? List.of() : requested;
        });
        service = new ConversationServiceImpl(
                repository,
                orchestrator,
                conversationTurnCodec,
                new ConversationRetrievalTraceBuilder(objectMapper),
                kbScopeResolver,
                objectMapper,
                meterRegistry,
                activityEventService,
                agentTaskRepository,
                null,
                Runnable::run
        );
        ReflectionTestUtils.setField(service, "agentConversationCleanupService", agentConversationCleanupService);
    }

    @Test
    void createMessage_shouldSkipRetrievalAndQuestionActivityForChat() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        when(conversationIntentRouter.route(eq(session.getSessionId()), eq("你好"))).thenReturn(
                new ConversationIntentResult(ConversationIntentType.CHAT, 1.0D,
                        "explicit_chat_rule", ConversationIntentSource.RULE, false));
        when(chatResponseService.generate(session.getSessionId(), "你好")).thenReturn(
                new ChatResponseResult("你好！有什么想了解的吗？", AnswerStatus.ANSWERED, null));

        ConversationMessageResponseDTO response = service.createMessage(
                session.getSessionId(), buildMessageRequest("你好"));

        assertThat(response.getIntent().getType()).isEqualTo("CHAT");
        assertThat(response.getRetrievalStage()).isEqualTo("SKIPPED");
        assertThat(response.getRewrittenQuery()).isNull();
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getResultCards()).isEmpty();
        verify(queryRewriteService, never()).rewrite(any(), any());
        verify(activityEventService, never()).recordQuestionAsked(any(), any(), any(), any());

        ConversationTurnDTO stored = service.listMessages(session.getSessionId(), 20, null).getTurns().getFirst();
        assertThat(stored.getIntent().getType()).isEqualTo("CHAT");
        assertThat(stored.getIntent().getSource()).isEqualTo("RULE");
        assertThat(service.getSession(session.getSessionId()).getTitle()).isEqualTo("你好");
    }

    @Test
    void createMessage_shouldReturnClarificationWithoutModelOrRetrievalForOther() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        when(conversationIntentRouter.route(eq(session.getSessionId()), eq("帮我查天气"))).thenReturn(
                new ConversationIntentResult(ConversationIntentType.OTHER, 0.95D,
                        "需要外部天气能力", ConversationIntentSource.MODEL, false));

        ConversationMessageResponseDTO response = service.createMessage(
                session.getSessionId(), buildMessageRequest("帮我查天气"));

        assertThat(response.getIntent().getType()).isEqualTo("OTHER");
        assertThat(response.getRetrievalStage()).isEqualTo("SKIPPED");
        assertThat(response.getAnswer()).contains("知识库");
        assertThat(response.getCitations()).isEmpty();
        verify(chatResponseService, never()).generate(any(), any());
        verify(queryRewriteService, never()).rewrite(any(), any());
        verify(activityEventService, never()).recordQuestionAsked(any(), any(), any(), any());
    }

    @Test
    void createMessage_shouldRecordOriginalQuestionForAgentExecution() {
        ConversationMessageOrchestrator agentOrchestrator = mock(ConversationMessageOrchestrator.class);
        when(agentOrchestrator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildAgentExecutionResult(AnswerStatus.ANSWERED, null));
        ConversationServiceImpl agentService = buildService(agentOrchestrator);
        ConversationSessionDTO session = agentService.createSession(new ConversationCreateRequestDTO());
        ConversationMessageRequestDTO request = buildMessageRequest("  对话框里的原始问题  ");
        request.setAgentEnabled(true);

        ConversationMessageResponseDTO response = agentService.createMessage(session.getSessionId(), request);

        verify(activityEventService).recordQuestionAsked(
                session.getSessionId(),
                response.getTurnId(),
                "对话框里的原始问题",
                List.of());
        assertThat(repository.findRecentTurns(session.getSessionId(), 1).getFirst().getQuery())
                .isEqualTo("对话框里的原始问题");
    }

    @Test
    void createMessage_shouldRecordDeferredAgentQuestionOnlyOnce() {
        ConversationMessageOrchestrator agentOrchestrator = mock(ConversationMessageOrchestrator.class);
        AgentDeferredTask deferredTask = new AgentDeferredTask("task-1", "DOCUMENT_SUMMARY", "{}");
        when(agentOrchestrator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildAgentExecutionResult(AnswerStatus.PROCESSING, deferredTask));
        ConversationServiceImpl agentService = buildService(agentOrchestrator);
        ConversationSessionDTO session = agentService.createSession(new ConversationCreateRequestDTO());
        ConversationMessageRequestDTO request = buildMessageRequest("总结这些文档");
        request.setAgentEnabled(true);

        ConversationMessageResponseDTO response = agentService.createMessage(session.getSessionId(), request);

        verify(activityEventService).recordQuestionAsked(
                session.getSessionId(),
                response.getTurnId(),
                "总结这些文档",
                List.of());
        verify(agentTaskRepository).save(any(AgentTask.class));
    }

    @Test
    void createMessage_shouldKeepIntentResolutionSeparateFromRetrievalRewrite() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();
        when(conversationIntentRouter.route(sessionId, "就按刚才的方案")).thenReturn(
                new ConversationIntentResult(ConversationIntentType.KB_QUERY, 0.96D,
                        "已从上下文还原完整请求", ConversationIntentSource.MODEL, false));
        RewriteResult rewrite = buildRewrite(
                "就按刚才的方案",
                "Docker 部署方案的具体步骤",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(sessionId, "就按刚才的方案")).thenReturn(rewrite);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("Docker 部署方案的具体步骤"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of()));
        when(answerGenerationService.generate(
                eq("就按刚才的方案"),
                eq("Docker 部署方案的具体步骤"),
                eq(AnswerMode.STRICT), anyList(), anyList()
        )).thenReturn(buildAnswer("未找到相关内容。", true, "no_evidence", List.of()));

        ConversationMessageResponseDTO response = service.createMessage(
                sessionId, buildMessageRequest("就按刚才的方案"));

        assertThat(response.getRewrittenQuery()).isEqualTo("Docker 部署方案的具体步骤");
        assertThat(response.getRetrievalTrace().getRewriteReason()).isEqualTo("rewrite_by_model");
        verify(queryRewriteService).rewrite(sessionId, "就按刚才的方案");
    }

    @Test
    void createMessage_shouldSupportMultiTurnAndPersistTrace() throws Exception {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();

        RewriteResult rewriteFirstTurn = buildRewrite(
                "mysql 架构是什么",
                "mysql 架构是什么 核心组件",
                "rewrite_by_model",
                false
        );
        RewriteResult rewriteSecondTurn = buildRewrite(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql 架构是什么"))).thenReturn(rewriteFirstTurn);
        when(queryRewriteService.rewrite(eq(sessionId), eq("那 InnoDB 呢"))).thenReturn(rewriteSecondTurn);

        ConversationRetrievalResult firstRetrieval = buildRetrievalResult(List.of(
                buildResult("seg_text_1", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-notes.pdf", "mysql 架构三层", 3),
                buildResult("seg_image_1", "asset_2", "IMAGE_CAPTION", "oss://bucket/mysql-diagram.png", "mysql 架构图", null)
        ));
        ConversationRetrievalResult secondRetrieval = buildRetrievalResult(List.of(
                buildResult("seg_text_2", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-notes.pdf", "InnoDB 支持事务与行锁", 12)
        ));
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构是什么 核心组件"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(firstRetrieval);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构中的 InnoDB 作用"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(secondRetrieval);

        when(answerGenerationService.generate(eq("mysql 架构是什么"), eq("mysql 架构是什么 核心组件"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildAnswer("MySQL 架构通常由连接层、SQL 层、存储引擎层组成。[1]", false, null, List.of("seg_text_1")));
        when(answerGenerationService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildAnswer("InnoDB 是默认事务引擎，支持行级锁与崩溃恢复。[1]", false, null, List.of("seg_text_2")));

        ConversationMessageResponseDTO firstResponse = service.createMessage(sessionId, buildMessageRequest("mysql 架构是什么"));
        ConversationMessageResponseDTO secondResponse = service.createMessage(sessionId, buildMessageRequest("那 InnoDB 呢"));

        assertThat(firstResponse.getSessionId()).isEqualTo(sessionId);
        assertThat(firstResponse.getAnswerStatus()).isEqualTo(AnswerStatus.ANSWERED.name());
        assertThat(firstResponse.getCitations()).hasSize(1);
        assertThat(secondResponse.getSessionId()).isEqualTo(sessionId);
        assertThat(secondResponse.getRewrittenQuery()).isEqualTo("mysql 架构中的 InnoDB 作用");
        assertThat(secondResponse.getCitations()).hasSize(1);
        assertThat(secondResponse.getCitations().getFirst().getFileName()).isEqualTo("mysql-notes.pdf");
        assertThat(firstResponse.getResultCards()).hasSize(2);
        assertThat(firstResponse.getResultCards()).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_1", "asset_2");
        assertThat(firstResponse.getResultCards().getFirst().getPrimaryHit().getSegmentId()).isEqualTo("seg_text_1");
        assertThat(firstResponse.getResultCards().getFirst().getPrimaryHit().getAnchor().getPageNo()).isEqualTo(3);
        assertThat(secondResponse.getRetrievalTrace().getLimit()).isEqualTo(20);
        assertThat(secondResponse.getRetrievalTrace().getRewriteReason()).isEqualTo("rewrite_by_model");
        assertThat(secondResponse.getRetrievalTrace().getRetrievedCount()).isEqualTo(1);
        assertThat(secondResponse.getRetrievalTrace().getTopSegmentIds()).containsExactly("seg_text_2");
        assertThat(secondResponse.getRetrievalTrace().getTopHitSources()).contains("VECTOR", "CONTENT");
        assertThat(service.getSession(sessionId).getTitle()).isEqualTo("mysql 架构是什么 核心组件");

        ConversationTurnListDTO messageList = service.listMessages(sessionId, 20, null);
        assertThat(messageList.getTurns()).hasSize(2);
        assertThat(messageList.getTurns().getFirst().getQuery()).isEqualTo("mysql 架构是什么");
        assertThat(messageList.getTurns().getFirst().getAnswerStatus()).isEqualTo(AnswerStatus.ANSWERED.name());
        assertThat(messageList.getTurns().get(1).getQuery()).isEqualTo("那 InnoDB 呢");
        assertThat(messageList.isHasMore()).isFalse();
        assertThat(messageList.getNextBeforeTurnId()).isNull();

        List<ConversationTurn> storedTurns = repository.findRecentTurns(sessionId, 10);
        assertThat(storedTurns).hasSize(2);
        ConversationTurn latestTurn = storedTurns.getFirst();
        List<ResultCardDTO> persistedResultCards = objectMapper.readValue(latestTurn.getResultCardsJson(), new TypeReference<>() {
        });
        assertThat(persistedResultCards).hasSize(1);
        assertThat(persistedResultCards.getFirst().getPrimaryHit().getSegmentId()).isEqualTo("seg_text_2");
        Map<String, Object> trace = objectMapper.readValue(latestTurn.getRetrievalTraceJson(), new TypeReference<>() {
        });
        assertThat(trace.get("rewriteFallback")).isEqualTo(false);
        assertThat(trace.get("answerFallback")).isEqualTo(false);
        assertThat(trace.get("answerFallbackReason")).isNull();
        assertThat(trace.get("retrievedCount")).isEqualTo(1);
        assertThat(meterRegistry.counter("conversation.turn.count").count()).isEqualTo(2.0d);
    }

    @Test
    void streamMessage_shouldGenerateFinalizedAnswerBeforePublishingText() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();
        when(queryRewriteService.rewrite(sessionId, "mysql 架构是什么")).thenReturn(buildRewrite(
                "mysql 架构是什么", "mysql 架构是什么", "unchanged", false));
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构是什么"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of()));
        when(answerGenerationService.generate(
                eq("mysql 架构是什么"), eq("mysql 架构是什么"), eq(AnswerMode.STRICT), anyList(), anyList()
        )).thenReturn(buildAnswer("最终规范回答", false, null, List.of()));

        SseEmitter emitter = service.streamMessage(sessionId, buildMessageRequest("mysql 架构是什么"));

        verify(answerGenerationService).generate(
                eq("mysql 架构是什么"), eq("mysql 架构是什么"), eq(AnswerMode.STRICT), anyList(), anyList());
        verify(answerGenerationService, never()).generateStream(
                any(), any(), any(), anyList(), anyList(), any());

        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> earlyEvents =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(
                        emitter, "earlySendAttempts");
        assertThat(earlyEvents).isNotNull();
        Map<?, ?> initialTrace = earlyEvents.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        ConversationTurn storedTurn = repository.findRecentTurns(sessionId, 1).getFirst();
        assertThat(initialTrace.get("turnId")).isEqualTo(storedTurn.getTurnId());

        ConversationTurnDTO recovered = service.getMessage(sessionId, storedTurn.getTurnId());
        assertThat(recovered.getTurnId()).isEqualTo(storedTurn.getTurnId());
        assertThat(recovered.getAnswer()).isEqualTo("最终规范回答");
        assertThat(recovered.getExecutionMode()).isEqualTo(ConversationExecutionMode.TRADITIONAL.name());
    }

    @Test
    void createMessage_shouldFallbackWhenEvidenceIsEmpty() throws Exception {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();

        RewriteResult rewriteResult = buildRewrite(
                "它和 buffer pool 有什么关系",
                "mysql 架构中 InnoDB 与 buffer pool 的关系",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("它和 buffer pool 有什么关系"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构中 InnoDB 与 buffer pool 的关系"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of()));
        when(answerGenerationService.generate(
                eq("它和 buffer pool 有什么关系"),
                eq("mysql 架构中 InnoDB 与 buffer pool 的关系"),
                eq(AnswerMode.STRICT),
                anyList(),
                anyList()
        )).thenReturn(buildAnswer("未找到足够内容支持该问题。请尝试缩小范围或补充关键词。", true, "no_evidence", List.of()));

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("它和 buffer pool 有什么关系"));

        assertThat(response.getSessionId()).isEqualTo(sessionId);
        assertThat(response.getAnswerStatus()).isEqualTo(AnswerStatus.NO_EVIDENCE.name());
        assertThat(response.getAnswerFallbackReason()).isEqualTo("no_evidence");
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getResultCards()).isEmpty();
        assertThat(response.getAnswer()).contains("未找到足够内容支持该问题");
        assertThat(response.getRetrievalTrace().getRetrievedCount()).isEqualTo(0);
        assertThat(response.getRetrievalTrace().getAnswerFallback()).isTrue();
        assertThat(response.getRetrievalTrace().getAnswerFallbackReason()).isEqualTo("no_evidence");

        List<ConversationTurn> storedTurns = repository.findRecentTurns(sessionId, 10);
        assertThat(storedTurns).hasSize(1);
        ConversationTurn storedTurn = storedTurns.getFirst();
        assertThat(storedTurn.getAnswerStatus()).isEqualTo(AnswerStatus.NO_EVIDENCE.name());
        assertThat(storedTurn.getAnswerFallbackReason()).isEqualTo("no_evidence");
        List<ResultCardDTO> persistedResultCards = objectMapper.readValue(storedTurn.getResultCardsJson(), new TypeReference<>() {
        });
        assertThat(persistedResultCards).isEmpty();
        Map<String, Object> trace = objectMapper.readValue(storedTurn.getRetrievalTraceJson(), new TypeReference<>() {
        });
        assertThat(trace.get("retrievedCount")).isEqualTo(0);
        assertThat(trace.get("answerFallback")).isEqualTo(true);
        assertThat(trace.get("answerFallbackReason")).isEqualTo("no_evidence");
        assertThat(meterRegistry.counter("answer.citation.empty.count").count()).isEqualTo(1.0d);
    }

    @Test
    void createMessage_shouldNotInheritLegacySessionAssetScope() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        ConversationSession storedSession = repository.findSession(session.getSessionId()).orElseThrow();
        storedSession.setAssetScope(List.of("legacy_asset"));
        repository.saveSession(storedSession);

        ConversationMessageRequestDTO request = buildMessageRequest("只查询当前消息范围");
        when(queryRewriteService.rewrite(eq(session.getSessionId()), eq(request.getQuery())))
                .thenReturn(buildRewrite(request.getQuery(), request.getQuery(), "original_query", true));
        when(conversationRetrievalOrchestrator.retrieve(
                eq(request.getQuery()), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of()));
        when(answerGenerationService.generate(
                eq(request.getQuery()), eq(request.getQuery()), eq(AnswerMode.STRICT), anyList(), anyList()
        )).thenReturn(buildAnswer("未找到相关内容。", true, "no_evidence", List.of()));

        ConversationMessageResponseDTO response = service.createMessage(session.getSessionId(), request);

        assertThat(response.getAssetScope()).isNullOrEmpty();
        assertThat(service.listMessages(session.getSessionId(), 20, null).getTurns().getFirst().getAssetScope())
                .isEmpty();
    }

    @Test
    void createMessage_shouldGenerateAnswerOnlyFromResultCardSegments() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();

        RewriteResult rewriteResult = buildRewrite(
                "mysql 索引有哪些",
                "mysql 索引 类型 适用场景",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql 索引有哪些"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 索引 类型 适用场景"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_asset_1", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-1.pdf", "BTree 索引适合范围查询", 1),
                buildResult("seg_asset_2", "asset_2", "TEXT_CHUNK", "oss://bucket/mysql-2.pdf", "Hash 索引适合等值查询", 2),
                buildResult("seg_asset_3", "asset_3", "TEXT_CHUNK", "oss://bucket/mysql-3.pdf", "全文索引用于文本匹配", 3),
                buildResult("seg_asset_4", "asset_4", "TEXT_CHUNK", "oss://bucket/mysql-4.pdf", "空间索引用于地理数据", 4)
        )));
        when(answerGenerationService.generate(eq("mysql 索引有哪些"), eq("mysql 索引 类型 适用场景"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildAnswer("MySQL 常见索引包括 BTree、Hash 和全文索引。[1][2][3]", false, null,
                        List.of("seg_asset_1", "seg_asset_2", "seg_asset_3")));

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("mysql 索引有哪些"));

        assertThat(response.getResultCards()).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_1", "asset_2", "asset_3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationRetrievalCandidate>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationCitation>> citationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(answerGenerationService).generate(
                eq("mysql 索引有哪些"),
                eq("mysql 索引 类型 适用场景"),
                eq(AnswerMode.STRICT),
                candidatesCaptor.capture(),
                citationsCaptor.capture()
        );
        assertThat(candidatesCaptor.getValue()).extracting(ConversationRetrievalCandidate::getSegmentId)
                .containsExactly("seg_asset_1", "seg_asset_2", "seg_asset_3");
        assertThat(citationsCaptor.getValue()).extracting(ConversationCitation::getSegmentId)
                .containsExactly("seg_asset_1", "seg_asset_2", "seg_asset_3");
    }

    @Test
    void createMessage_shouldKeepResultCardsAndSkipCitationsWhenAnswerGenerationFails() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();

        RewriteResult rewriteResult = buildRewrite(
                "mysql redo log 是什么",
                "mysql redo log 作用",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql redo log 是什么"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql redo log 作用"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_redo_1", "asset_redo_1", "TEXT_CHUNK", "oss://bucket/mysql-redo.pdf", "redo log 保障崩溃恢复", 8),
                buildResult("seg_redo_2", "asset_redo_2", "TEXT_CHUNK", "oss://bucket/mysql-log.pdf", "redo log 先写日志再刷盘", 9)
        )));
        when(answerGenerationService.generate(eq("mysql redo log 是什么"), eq("mysql redo log 作用"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildGenerationFailure("model_unavailable"));

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("mysql redo log 是什么"));

        assertThat(response.getResultCards()).hasSize(2);
        assertThat(response.getAnswer()).isEqualTo("回答模型未能生成可靠结果，请稍后重试。");
        assertThat(response.getAnswerStatus()).isEqualTo(AnswerStatus.GENERATION_FAILED.name());
        assertThat(response.getAnswerFallbackReason()).isEqualTo("model_unavailable");
        assertThat(response.getExecutionMode()).isEqualTo(ConversationExecutionMode.TRADITIONAL.name());
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getResultCards()).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_redo_1", "asset_redo_2");
        assertThat(response.getRetrievalTrace().getAnswerFallback()).isFalse();
        assertThat(response.getRetrievalTrace().getAnswerFallbackReason()).isEqualTo("model_unavailable");
        verify(citationReasonGenerationService, never()).generate(any());
    }

    @Test
    void createMessage_shouldKeepSameAssetHitsInOneResultCard() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();

        RewriteResult rewriteResult = buildRewrite(
                "mysql buffer pool",
                "mysql buffer pool 机制",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql buffer pool"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql buffer pool 机制"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_pool_1", "asset_pool", "TEXT_CHUNK", "oss://bucket/mysql-pool.pdf", "buffer pool 缓存数据页", 3),
                buildResult("seg_pool_2", "asset_pool", "TEXT_CHUNK", "oss://bucket/mysql-pool.pdf", "buffer pool 使用 LRU 链表", 4)
        )));
        when(answerGenerationService.generate(eq("mysql buffer pool"), eq("mysql buffer pool 机制"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildAnswer("Buffer pool 用于缓存数据页和索引页。[1]", false, null,
                        List.of("seg_pool_1", "seg_pool_2")));
        when(citationReasonGenerationService.generate(any())).thenReturn(Map.of(
                "seg_pool_1", "该段说明 Buffer Pool 用于缓存数据页。",
                "seg_pool_2", "该段说明 Buffer Pool 使用 LRU 管理缓存。"
        ));

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("mysql buffer pool"));

        assertThat(response.getResultCards()).hasSize(1);
        ResultCardDTO card = response.getResultCards().getFirst();
        assertThat(card.getPrimaryHit().getSegmentId()).isEqualTo("seg_pool_1");
        assertThat(card.getAdditionalHits()).hasSize(1);
        assertThat(card.getAdditionalHits().getFirst().getSegmentId()).isEqualTo("seg_pool_2");
        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getAssetId()).isEqualTo("asset_pool");
            assertThat(citation.getCitationIndex()).isEqualTo(1);
            assertThat(citation.getChunks()).extracting(ConversationTurnDTO.CitationChunkDTO::getSegmentId)
                    .containsExactly("seg_pool_1", "seg_pool_2");
            assertThat(citation.getChunks()).extracting(chunk -> chunk.getWhy().getReason())
                    .containsExactly(
                            "该段说明 Buffer Pool 用于缓存数据页。",
                            "该段说明 Buffer Pool 使用 LRU 管理缓存。"
                    );
        });
        assertThat(service.listMessages(sessionId, 20, null).getTurns())
                .singleElement()
                .satisfies(turn -> assertThat(turn.getCitations())
                        .flatExtracting(ConversationTurnDTO.CitationDTO::getChunks)
                        .extracting(chunk -> chunk.getWhy().getReason())
                        .containsExactly(
                                "该段说明 Buffer Pool 用于缓存数据页。",
                                "该段说明 Buffer Pool 使用 LRU 管理缓存。"
                        ));
    }

    @Test
    void createMessage_shouldNormalizeAnswerModeAndPassItToGenerator() throws Exception {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();
        RewriteResult rewriteResult = buildRewrite(
                "mysql 总结一下",
                "mysql 总结 核心机制",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql 总结一下"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 总结 核心机制"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_summary_1", "asset_summary", "TEXT_CHUNK", "oss://bucket/mysql-summary.pdf", "MySQL 包含 SQL 层和存储引擎层", 1)
        )));
        when(answerGenerationService.generate(
                eq("mysql 总结一下"),
                eq("mysql 总结 核心机制"),
                eq(AnswerMode.SUMMARY),
                anyList(),
                anyList()
        )).thenReturn(buildAnswer("MySQL 可概括为 SQL 层和存储引擎层。[1]", false, null, List.of("seg_summary_1")));
        ConversationMessageRequestDTO request = buildMessageRequest("mysql 总结一下");
        request.setAnswerMode("summary");

        ConversationMessageResponseDTO response = service.createMessage(sessionId, request);

        assertThat(response.getAnswerMode()).isEqualTo("SUMMARY");
        assertThat(request.getAnswerMode()).isEqualTo("SUMMARY");
        ConversationTurn storedTurn = repository.findRecentTurns(sessionId, 10).getFirst();
        assertThat(storedTurn.getAnswerMode()).isEqualTo("SUMMARY");
        Map<String, Object> trace = objectMapper.readValue(storedTurn.getRetrievalTraceJson(), new TypeReference<>() {
        });
        assertThat(trace.get("answerMode")).isEqualTo("SUMMARY");
    }

    @Test
    void createMessage_shouldFallbackToStrictWhenAnswerModeIsUnsupported() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        String sessionId = session.getSessionId();
        RewriteResult rewriteResult = buildRewrite(
                "mysql 自由发挥",
                "mysql 自由发挥",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("mysql 自由发挥"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 自由发挥"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_strict_1", "asset_strict", "TEXT_CHUNK", "oss://bucket/mysql.pdf", "MySQL 是关系型数据库", 1)
        )));
        when(answerGenerationService.generate(
                eq("mysql 自由发挥"),
                eq("mysql 自由发挥"),
                eq(AnswerMode.STRICT),
                anyList(),
                anyList()
        )).thenReturn(buildAnswer("MySQL 是关系型数据库。[1]", false, null, List.of("seg_strict_1")));
        ConversationMessageRequestDTO request = buildMessageRequest("mysql 自由发挥");
        request.setAnswerMode("creative");

        ConversationMessageResponseDTO response = service.createMessage(sessionId, request);

        assertThat(response.getAnswerMode()).isEqualTo("STRICT");
        assertThat(request.getAnswerMode()).isEqualTo("STRICT");
    }

    @Test
    void listSessions_shouldReturnSingleUserSessionsWithCursorWithoutPerSessionMessageQueries() {
        ConversationSessionDTO first = service.createSession(new ConversationCreateRequestDTO());
        ConversationSessionDTO second = service.createSession(new ConversationCreateRequestDTO());
        repository.findSession(first.getSessionId()).orElseThrow().setUpdatedAt(1000L);
        repository.findSession(second.getSessionId()).orElseThrow().setUpdatedAt(2000L);

        ConversationSessionListDTO firstPage = service.listSessions(1, null);

        assertThat(firstPage.getItems()).hasSize(1);
        assertThat(firstPage.getItems().getFirst().getUserId()).isEqualTo("single_user");
        assertThat(firstPage.getItems().getFirst().getSessionId()).isEqualTo(second.getSessionId());
        assertThat(firstPage.getNextCursor()).isNotBlank();

        ConversationSessionListDTO secondPage = service.listSessions(1, firstPage.getNextCursor());

        assertThat(secondPage.getItems()).hasSize(1);
        assertThat(secondPage.getItems().getFirst().getSessionId()).isEqualTo(first.getSessionId());
        assertThat(secondPage.getItems().getFirst().getLastMessagePreview()).isNull();
        assertThat(secondPage.getNextCursor()).isNull();
    }

    @Test
    void renameAndDeleteSession_shouldDeleteConversationAndRelatedActivityRecords() {
        ConversationCreateRequestDTO createRequest = new ConversationCreateRequestDTO();
        createRequest.setTitle("原标题");
        ConversationSessionDTO session = service.createSession(createRequest);
        ConversationRenameRequestDTO renameRequest = new ConversationRenameRequestDTO();
        renameRequest.setTitle("新标题");

        ConversationSessionDTO renamed = service.renameSession(session.getSessionId(), renameRequest);

        assertThat(renamed.getTitle()).isEqualTo("新标题");

        service.deleteSession(session.getSessionId());

        assertThat(repository.findSession(session.getSessionId())).isEmpty();
        verify(agentConversationCleanupService).cancelRunning(session.getSessionId());
        verify(agentConversationCleanupService).deleteRecords(session.getSessionId());
        verify(activityEventService).deleteBySessionId(session.getSessionId());
    }

    @Test
    void createMessage_shouldKeepExistingTitleWhenAlreadyProvided() {
        ConversationCreateRequestDTO createRequest = new ConversationCreateRequestDTO();
        createRequest.setTitle("手动命名会话");
        ConversationSessionDTO session = service.createSession(createRequest);
        String sessionId = session.getSessionId();

        RewriteResult rewriteResult = buildRewrite(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                "rewrite_by_model",
                false
        );
        when(queryRewriteService.rewrite(eq(sessionId), eq("那 InnoDB 呢"))).thenReturn(rewriteResult);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构中的 InnoDB 作用"), eq(20), anyList(), anyList(), eq(null)
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_text_2", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-notes.pdf", "InnoDB 支持事务与行锁", 12)
        )));
        when(answerGenerationService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), eq(AnswerMode.STRICT), anyList(), anyList()))
                .thenReturn(buildAnswer("InnoDB 是默认事务引擎，支持行级锁与崩溃恢复。[1]", false, null, List.of("seg_text_2")));

        service.createMessage(sessionId, buildMessageRequest("那 InnoDB 呢"));

        assertThat(service.getSession(sessionId).getTitle()).isEqualTo("手动命名会话");
    }

    @Test
    void listMessages_shouldInferLegacyAnswerStatusWithoutReturningUnusedResultCards() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        ConversationTurn legacyTurn = new ConversationTurn();
        legacyTurn.setTurnId("turn_legacy");
        legacyTurn.setSessionId(session.getSessionId());
        legacyTurn.setQuery("legacy query");
        legacyTurn.setRewrittenQuery("legacy query");
        legacyTurn.setAnswer("legacy answer");
        legacyTurn.setCitationsJson("[]");
        legacyTurn.setRetrievalTraceJson("{\"answerFallback\":true,\"answerFallbackReason\":\"no_evidence_low_retrieval_score\"}");
        legacyTurn.setCreatedAt(System.currentTimeMillis());
        repository.saveTurn(legacyTurn);

        ConversationTurnListDTO response = service.listMessages(session.getSessionId(), 20, null);

        assertThat(response.getTurns()).hasSize(1);
        assertThat(response.getTurns().getFirst().getAnswerStatus()).isEqualTo(AnswerStatus.NO_EVIDENCE.name());
        assertThat(response.getTurns().getFirst().getAnswerFallbackReason())
                .isEqualTo("no_evidence_low_retrieval_score");
    }

    @Test
    void listMessages_shouldPageByCreatedAtAndTurnIdWithoutGaps() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        saveHistoryTurn(session.getSessionId(), "turn_1", 1_000L, AnswerStatus.ANSWERED, null);
        saveHistoryTurn(session.getSessionId(), "turn_2", 2_000L, AnswerStatus.ANSWERED, null);
        saveHistoryTurn(session.getSessionId(), "turn_3", 2_000L, AnswerStatus.ANSWERED, null);
        saveHistoryTurn(session.getSessionId(), "turn_4", 3_000L, AnswerStatus.ANSWERED, null);

        ConversationTurnListDTO first = service.listMessages(session.getSessionId(), 2, null);
        ConversationTurnListDTO second = service.listMessages(
                session.getSessionId(), 2, first.getNextBeforeTurnId());

        assertThat(first.getTurns()).extracting(ConversationTurnDTO::getTurnId)
                .containsExactly("turn_3", "turn_4");
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextBeforeTurnId()).isEqualTo("turn_3");
        assertThat(second.getTurns()).extracting(ConversationTurnDTO::getTurnId)
                .containsExactly("turn_1", "turn_2");
        assertThat(second.isHasMore()).isFalse();
        assertThat(second.getNextBeforeTurnId()).isNull();
    }

    @Test
    void listMessages_shouldBatchOnlyProcessingTasks() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        saveHistoryTurn(session.getSessionId(), "turn_done", 1_000L, AnswerStatus.ANSWERED, "task_done");
        saveHistoryTurn(session.getSessionId(), "turn_running", 2_000L, AnswerStatus.PROCESSING, "task_running");
        AgentTask running = new AgentTask();
        running.setTaskId("task_running");
        running.setStatus("RUNNING");
        running.setProgress(40);
        running.setCurrentStage("MAP_SUMMARY");
        running.setCitationsJson("[]");
        when(agentTaskRepository.findByIds(any())).thenReturn(List.of(running));

        ConversationTurnListDTO response = service.listMessages(session.getSessionId(), 20, null);

        assertThat(response.getTurns().getFirst().getAgentTask()).isNull();
        assertThat(response.getTurns().get(1).getAgentTask()).isNotNull();
        ConversationTurnDTO runningTurn = response.getTurns().get(1);
        assertThat(runningTurn.getAgentTask().getTaskId()).isEqualTo("task_running");
        assertThat(runningTurn.getAgentTask().getProgress()).isEqualTo(40);
        verify(agentTaskRepository).findByIds(org.mockito.ArgumentMatchers.argThat(ids ->
                ids.size() == 1 && ids.contains("task_running") && !ids.contains("task_done")));
        verify(agentTaskRepository, never()).findById("task_done");
    }

    private void saveHistoryTurn(String sessionId,
                                 String turnId,
                                 long createdAt,
                                 AnswerStatus status,
                                 String taskId) {
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(turnId);
        turn.setSessionId(sessionId);
        turn.setQuery("query " + turnId);
        turn.setAnswer(status == AnswerStatus.PROCESSING ? "" : "answer " + turnId);
        turn.setAnswerStatus(status.name());
        turn.setCitationsJson("[]");
        turn.setRetrievalTraceJson("{}");
        turn.setExecutionMode(taskId == null ? "TRADITIONAL" : "AGENT");
        turn.setAgentTaskId(taskId);
        turn.setCreatedAt(createdAt);
        repository.saveTurn(turn);
    }

    private RewriteResult buildRewrite(String originalQuery, String rewrittenQuery, String reason, boolean fallback) {
        RewriteResult result = new RewriteResult();
        result.setOriginalQuery(originalQuery);
        result.setRewrittenQuery(rewrittenQuery);
        result.setRewriteReason(reason);
        result.setTopicEntities(List.of("mysql", "innodb"));
        result.setConfidence(0.92d);
        result.setFallbackUsed(fallback);
        return result;
    }

    private ConversationRetrievalResult buildRetrievalResult(List<ConversationRetrievalCandidate> topCandidates) {
        ConversationRetrievalResult result = new ConversationRetrievalResult();
        result.setTopCandidates(topCandidates);
        return result;
    }

    private ConversationRetrievalCandidate buildResult(String segmentId,
                                                       String assetId,
                                                       String segmentType,
                                                       String sourceRef,
                                                       String snippet,
                                                       Integer pageNo) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .segmentType(segmentType)
                .snippet(snippet)
                .sourceRef(sourceRef)
                .pageNo(pageNo)
                .explain(ConversationRetrievalCandidate.Explain.builder()
                        .strategyEffective("KB_RRF_RERANK")
                        .hitSources(List.of("VECTOR", "CONTENT"))
                        .build())
                .build();
    }

    private AnswerGenerationResult buildAnswer(String answer, boolean fallback, String fallbackReason, List<String> segmentIds) {
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText(answer);
        result.setFallbackUsed(fallback);
        result.setFallbackReason(fallbackReason);
        result.setAnswerInputSegmentIds(segmentIds);
        return result;
    }

    private AnswerGenerationResult buildGenerationFailure(String reason) {
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText("回答模型未能生成可靠结果，请稍后重试。");
        result.setGenerationFailed(true);
        result.setFallbackReason(reason);
        result.setAnswerInputSegmentIds(List.of());
        return result;
    }

    private ConversationExecutionResult buildAgentExecutionResult(AnswerStatus status,
                                                                  AgentDeferredTask deferredTask) {
        return new ConversationExecutionResult(
                null,
                false,
                null,
                status == AnswerStatus.PROCESSING ? "任务已进入后台处理" : "Agent 回答",
                status,
                null,
                List.of(),
                List.of(),
                null,
                "run-1",
                "general-agent-v1",
                ConversationExecutionMode.AGENT,
                deferredTask);
    }

    private ConversationServiceImpl buildService(ConversationMessageOrchestrator orchestrator) {
        ConversationTurnCodec codec = new ConversationTurnCodec(objectMapper);
        ConversationServiceImpl builtService = new ConversationServiceImpl(
                repository,
                orchestrator,
                codec,
                new ConversationRetrievalTraceBuilder(objectMapper),
                kbScopeResolver,
                objectMapper,
                meterRegistry,
                activityEventService,
                agentTaskRepository,
                null,
                Runnable::run);
        ReflectionTestUtils.setField(builtService, "agentConversationCleanupService", agentConversationCleanupService);
        return builtService;
    }

    private ConversationMessageRequestDTO buildMessageRequest(String query) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery(query);
        request.setLimit(20);
        return request;
    }

    private static class InMemoryConversationRepository implements ConversationRepository {

        private final Map<String, ConversationSession> sessions = new HashMap<>();
        private final Map<String, LinkedHashMap<String, ConversationTurn>> turnsBySession = new HashMap<>();

        @Override
        public void saveSession(ConversationSession session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public Optional<ConversationSession> findSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public List<ConversationSession> findRecentSessions(String userId, int limit) {
            return sessions.values().stream()
                    .filter(session -> userId.equals(session.getUserId()))
                    .sorted(Comparator.comparingLong(ConversationSession::getUpdatedAt).reversed())
                    .limit(Math.max(1, limit))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        @Override
        public void deleteSession(String sessionId) {
            sessions.remove(sessionId);
            turnsBySession.remove(sessionId);
        }

        @Override
        public void saveTurn(ConversationTurn turn) {
            turnsBySession.computeIfAbsent(turn.getSessionId(), ignored -> new LinkedHashMap<>())
                    .put(turn.getTurnId(), turn);
        }

        @Override
        public Optional<ConversationTurn> findTurn(String sessionId, String turnId) {
            LinkedHashMap<String, ConversationTurn> turns = turnsBySession.get(sessionId);
            if (turns == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(turns.get(turnId));
        }

        @Override
        public List<ConversationTurn> findRecentTurns(String sessionId, int limit) {
            LinkedHashMap<String, ConversationTurn> turns = turnsBySession.get(sessionId);
            if (turns == null || turns.isEmpty()) {
                return List.of();
            }
            return turns.values().stream()
                    .sorted(Comparator.comparingLong(ConversationTurn::getCreatedAt).reversed())
                    .limit(Math.max(1, limit))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        @Override
        public List<ConversationTurn> findTurnPage(String sessionId,
                                                   ConversationTurnPosition before,
                                                   int limit) {
            LinkedHashMap<String, ConversationTurn> turns = turnsBySession.get(sessionId);
            if (turns == null || turns.isEmpty()) return List.of();
            return turns.values().stream()
                    .filter(turn -> before == null
                            || turn.getCreatedAt() < before.createdAt()
                            || (turn.getCreatedAt() == before.createdAt()
                            && turn.getTurnId().compareTo(before.turnId()) < 0))
                    .sorted(Comparator.comparingLong(ConversationTurn::getCreatedAt)
                            .thenComparing(ConversationTurn::getTurnId)
                            .reversed())
                    .limit(Math.max(1, limit))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }
}
