package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.FollowUpQuestionService;
import com.anchr.core.conversation.application.QueryRewriteService;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    @Mock
    private AnswerGenerationService answerGenerationService;
    @Mock
    private FollowUpQuestionService followUpQuestionService;
    @Mock
    private KbScopeResolver kbScopeResolver;
    @Mock
    private ActivityEventService activityEventService;

    private InMemoryConversationRepository repository;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConversationRepository();
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        ConversationMessagePipeline conversationMessagePipeline = new ConversationMessagePipeline(
                queryRewriteService,
                conversationRetrievalOrchestrator,
                new ConversationCitationMapper(),
                new ConversationResultCardMapper(),
                answerGenerationService
        );
        when(kbScopeResolver.resolveVisibleKbIds(any())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(0);
            return requested == null ? List.of() : requested;
        });
        service = new ConversationServiceImpl(
                repository,
                conversationMessagePipeline,
                followUpQuestionService,
                new ConversationTurnCodec(objectMapper),
                new ConversationRetrievalTraceBuilder(objectMapper),
                kbScopeResolver,
                objectMapper,
                meterRegistry,
                activityEventService,
                Runnable::run
        );
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
                eq("mysql 架构是什么 核心组件"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(firstRetrieval);
        when(conversationRetrievalOrchestrator.retrieve(
                eq("mysql 架构中的 InnoDB 作用"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(secondRetrieval);

        when(answerGenerationService.generate(eq("mysql 架构是什么"), eq("mysql 架构是什么 核心组件"), anyList(), anyList()))
                .thenReturn(buildAnswer("MySQL 架构通常由连接层、SQL 层、存储引擎层组成。[1]", false, null, List.of("seg_text_1")));
        when(answerGenerationService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), anyList(), anyList()))
                .thenReturn(buildAnswer("InnoDB 是默认事务引擎，支持行级锁与崩溃恢复。[1]", false, null, List.of("seg_text_2")));
        when(followUpQuestionService.generate(eq("mysql 架构是什么"), eq("mysql 架构是什么 核心组件"), anyList()))
                .thenReturn(List.of("《mysql-notes.pdf》里还有哪些和“mysql”直接相关的内容？", "“mysql”和“InnoDB”之间的关系是什么？"));
        when(followUpQuestionService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), anyList()))
                .thenReturn(List.of("在《mysql-notes.pdf》第12页，关于“InnoDB”还有哪些关键点？", "有没有“InnoDB”对应的结构图或示意图可对照理解？"));

        ConversationMessageResponseDTO firstResponse = service.createMessage(sessionId, buildMessageRequest("mysql 架构是什么"));
        ConversationMessageResponseDTO secondResponse = service.createMessage(sessionId, buildMessageRequest("那 InnoDB 呢"));

        assertThat(firstResponse.getSessionId()).isEqualTo(sessionId);
        assertThat(secondResponse.getSessionId()).isEqualTo(sessionId);
        assertThat(secondResponse.getRewrittenQuery()).isEqualTo("mysql 架构中的 InnoDB 作用");
        assertThat(secondResponse.getCitations()).hasSize(1);
        assertThat(secondResponse.getCitations().getFirst().getFileName()).isEqualTo("mysql-notes.pdf");
        assertThat(firstResponse.getResultCards()).hasSize(2);
        assertThat(firstResponse.getResultCards()).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_1", "asset_2");
        assertThat(firstResponse.getResultCards().getFirst().getPrimaryHit().getSegmentId()).isEqualTo("seg_text_1");
        assertThat(firstResponse.getResultCards().getFirst().getPrimaryHit().getAnchor().getPageNo()).isEqualTo(3);
        assertThat(secondResponse.getRetrievalTrace().getTopK()).isEqualTo(60);
        assertThat(secondResponse.getRetrievalTrace().getRewriteReason()).isEqualTo("rewrite_by_model");
        assertThat(secondResponse.getRetrievalTrace().getRetrievedCount()).isEqualTo(1);
        assertThat(secondResponse.getRetrievalTrace().getTopSegmentIds()).containsExactly("seg_text_2");
        assertThat(secondResponse.getRetrievalTrace().getTopHitSources()).contains("VECTOR", "CONTENT");
        assertThat(secondResponse.getSuggestedQuestions()).hasSize(2);
        assertThat(secondResponse.getSuggestedQuestions().getFirst()).contains("mysql-notes.pdf");
        assertThat(service.getSession(sessionId).getTitle()).isEqualTo("mysql 架构是什么 核心组件");

        ConversationTurnListDTO messageList = service.listMessages(sessionId, 20, null);
        assertThat(messageList.getTurns()).hasSize(2);
        assertThat(messageList.getTurns().getFirst().getQuery()).isEqualTo("mysql 架构是什么");
        assertThat(messageList.getTurns().get(1).getQuery()).isEqualTo("那 InnoDB 呢");
        assertThat(messageList.getTurns().getFirst().getResultCards()).hasSize(2);
        assertThat(messageList.getTurns().getFirst().getResultCards().getFirst().getPrimaryHit().getSegmentId())
                .isEqualTo("seg_text_1");
        assertThat(messageList.getTurns().get(1).getResultCards()).hasSize(1);
        assertThat(messageList.getTurns().get(1).getResultCards().getFirst().getPrimaryHit().getSegmentId())
                .isEqualTo("seg_text_2");

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
                eq("mysql 架构中 InnoDB 与 buffer pool 的关系"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(buildRetrievalResult(List.of()));
        when(answerGenerationService.generate(
                eq("它和 buffer pool 有什么关系"), eq("mysql 架构中 InnoDB 与 buffer pool 的关系"), anyList(), anyList()
        )).thenReturn(buildAnswer("未找到足够内容支持该问题。请尝试缩小范围或补充关键词。", true, "no_evidence", List.of()));
        when(followUpQuestionService.generate(eq("它和 buffer pool 有什么关系"), eq("mysql 架构中 InnoDB 与 buffer pool 的关系"), anyList()))
                .thenReturn(List.of());

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("它和 buffer pool 有什么关系"));

        assertThat(response.getSessionId()).isEqualTo(sessionId);
        assertThat(response.getCitations()).isEmpty();
        assertThat(response.getResultCards()).isEmpty();
        assertThat(response.getAnswer()).contains("未找到足够内容支持该问题");
        assertThat(response.getRetrievalTrace().getRetrievedCount()).isEqualTo(0);
        assertThat(response.getRetrievalTrace().getAnswerFallback()).isTrue();
        assertThat(response.getRetrievalTrace().getAnswerFallbackReason()).isEqualTo("no_evidence");
        assertThat(response.getSuggestedQuestions()).isEmpty();

        List<ConversationTurn> storedTurns = repository.findRecentTurns(sessionId, 10);
        assertThat(storedTurns).hasSize(1);
        ConversationTurn storedTurn = storedTurns.getFirst();
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
                eq("mysql 索引 类型 适用场景"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_asset_1", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-1.pdf", "BTree 索引适合范围查询", 1),
                buildResult("seg_asset_2", "asset_2", "TEXT_CHUNK", "oss://bucket/mysql-2.pdf", "Hash 索引适合等值查询", 2),
                buildResult("seg_asset_3", "asset_3", "TEXT_CHUNK", "oss://bucket/mysql-3.pdf", "全文索引用于文本匹配", 3),
                buildResult("seg_asset_4", "asset_4", "TEXT_CHUNK", "oss://bucket/mysql-4.pdf", "空间索引用于地理数据", 4)
        )));
        when(answerGenerationService.generate(eq("mysql 索引有哪些"), eq("mysql 索引 类型 适用场景"), anyList(), anyList()))
                .thenReturn(buildAnswer("MySQL 常见索引包括 BTree、Hash 和全文索引。[1][2][3]", false, null,
                        List.of("seg_asset_1", "seg_asset_2", "seg_asset_3")));
        when(followUpQuestionService.generate(eq("mysql 索引有哪些"), eq("mysql 索引 类型 适用场景"), anyList()))
                .thenReturn(List.of());

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
                candidatesCaptor.capture(),
                citationsCaptor.capture()
        );
        assertThat(candidatesCaptor.getValue()).extracting(ConversationRetrievalCandidate::getSegmentId)
                .containsExactly("seg_asset_1", "seg_asset_2", "seg_asset_3");
        assertThat(citationsCaptor.getValue()).extracting(ConversationCitation::getSegmentId)
                .containsExactly("seg_asset_1", "seg_asset_2", "seg_asset_3");
    }

    @Test
    void createMessage_shouldReturnResultCardsWhenAnswerGenerationFallback() {
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
                eq("mysql redo log 作用"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_redo_1", "asset_redo_1", "TEXT_CHUNK", "oss://bucket/mysql-redo.pdf", "redo log 保障崩溃恢复", 8),
                buildResult("seg_redo_2", "asset_redo_2", "TEXT_CHUNK", "oss://bucket/mysql-log.pdf", "redo log 先写日志再刷盘", 9)
        )));
        when(answerGenerationService.generate(eq("mysql redo log 是什么"), eq("mysql redo log 作用"), anyList(), anyList()))
                .thenReturn(buildAnswer("根据当前知识库，先给出可确认的信息：", true, "model_unavailable",
                        List.of("seg_redo_1", "seg_redo_2")));
        when(followUpQuestionService.generate(eq("mysql redo log 是什么"), eq("mysql redo log 作用"), anyList()))
                .thenReturn(List.of());

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("mysql redo log 是什么"));

        assertThat(response.getResultCards()).hasSize(2);
        assertThat(response.getResultCards()).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_redo_1", "asset_redo_2");
        assertThat(response.getRetrievalTrace().getAnswerFallback()).isTrue();
        assertThat(response.getRetrievalTrace().getAnswerFallbackReason()).isEqualTo("model_unavailable");
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
                eq("mysql buffer pool 机制"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_pool_1", "asset_pool", "TEXT_CHUNK", "oss://bucket/mysql-pool.pdf", "buffer pool 缓存数据页", 3),
                buildResult("seg_pool_2", "asset_pool", "TEXT_CHUNK", "oss://bucket/mysql-pool.pdf", "buffer pool 使用 LRU 链表", 4)
        )));
        when(answerGenerationService.generate(eq("mysql buffer pool"), eq("mysql buffer pool 机制"), anyList(), anyList()))
                .thenReturn(buildAnswer("Buffer pool 用于缓存数据页和索引页。[1]", false, null, List.of("seg_pool_1")));
        when(followUpQuestionService.generate(eq("mysql buffer pool"), eq("mysql buffer pool 机制"), anyList()))
                .thenReturn(List.of());

        ConversationMessageResponseDTO response = service.createMessage(sessionId, buildMessageRequest("mysql buffer pool"));

        assertThat(response.getResultCards()).hasSize(1);
        ResultCardDTO card = response.getResultCards().getFirst();
        assertThat(card.getPrimaryHit().getSegmentId()).isEqualTo("seg_pool_1");
        assertThat(card.getAdditionalHits()).hasSize(1);
        assertThat(card.getAdditionalHits().getFirst().getSegmentId()).isEqualTo("seg_pool_2");
    }

    @Test
    void listSessions_shouldReturnSingleUserSessionsWithCursorAndPreview() {
        ConversationSessionDTO first = service.createSession(new ConversationCreateRequestDTO());
        ConversationSessionDTO second = service.createSession(new ConversationCreateRequestDTO());
        repository.findSession(first.getSessionId()).orElseThrow().setUpdatedAt(1000L);
        repository.findSession(second.getSessionId()).orElseThrow().setUpdatedAt(2000L);

        ConversationTurn firstTurn = new ConversationTurn();
        firstTurn.setTurnId("turn_preview");
        firstTurn.setSessionId(first.getSessionId());
        firstTurn.setQuery("preview query");
        firstTurn.setAnswer("preview answer ".repeat(10));
        firstTurn.setCreatedAt(System.currentTimeMillis());
        repository.saveTurn(firstTurn);

        ConversationSessionListDTO firstPage = service.listSessions(1, null);

        assertThat(firstPage.getItems()).hasSize(1);
        assertThat(firstPage.getItems().getFirst().getUserId()).isEqualTo("single_user");
        assertThat(firstPage.getItems().getFirst().getSessionId()).isEqualTo(second.getSessionId());
        assertThat(firstPage.getNextCursor()).isNotBlank();

        ConversationSessionListDTO secondPage = service.listSessions(1, firstPage.getNextCursor());

        assertThat(secondPage.getItems()).hasSize(1);
        assertThat(secondPage.getItems().getFirst().getSessionId()).isEqualTo(first.getSessionId());
        assertThat(secondPage.getItems().getFirst().getLastMessagePreview()).startsWith("preview answer");
        assertThat(secondPage.getNextCursor()).isNull();
    }

    @Test
    void renameAndDeleteSession_shouldOperateInSingleUserSpace() {
        ConversationCreateRequestDTO createRequest = new ConversationCreateRequestDTO();
        createRequest.setTitle("原标题");
        ConversationSessionDTO session = service.createSession(createRequest);
        ConversationRenameRequestDTO renameRequest = new ConversationRenameRequestDTO();
        renameRequest.setTitle("新标题");

        ConversationSessionDTO renamed = service.renameSession(session.getSessionId(), renameRequest);

        assertThat(renamed.getTitle()).isEqualTo("新标题");

        service.deleteSession(session.getSessionId());

        assertThat(repository.findSession(session.getSessionId())).isEmpty();
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
                eq("mysql 架构中的 InnoDB 作用"), eq(60), eq(20), eq("KB_RRF_RERANK"), anyList(), anyList()
        )).thenReturn(buildRetrievalResult(List.of(
                buildResult("seg_text_2", "asset_1", "TEXT_CHUNK", "oss://bucket/mysql-notes.pdf", "InnoDB 支持事务与行锁", 12)
        )));
        when(answerGenerationService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), anyList(), anyList()))
                .thenReturn(buildAnswer("InnoDB 是默认事务引擎，支持行级锁与崩溃恢复。[1]", false, null, List.of("seg_text_2")));
        when(followUpQuestionService.generate(eq("那 InnoDB 呢"), eq("mysql 架构中的 InnoDB 作用"), anyList()))
                .thenReturn(List.of("在《mysql-notes.pdf》第12页，关于“InnoDB”还有哪些关键点？", "有没有“InnoDB”对应的结构图或示意图可对照理解？"));

        service.createMessage(sessionId, buildMessageRequest("那 InnoDB 呢"));

        assertThat(service.getSession(sessionId).getTitle()).isEqualTo("手动命名会话");
    }

    @Test
    void listMessages_shouldReturnEmptyResultCardsForLegacyTurn() {
        ConversationSessionDTO session = service.createSession(new ConversationCreateRequestDTO());
        ConversationTurn legacyTurn = new ConversationTurn();
        legacyTurn.setTurnId("turn_legacy");
        legacyTurn.setSessionId(session.getSessionId());
        legacyTurn.setQuery("legacy query");
        legacyTurn.setRewrittenQuery("legacy query");
        legacyTurn.setAnswer("legacy answer");
        legacyTurn.setCitationsJson("[]");
        legacyTurn.setCreatedAt(System.currentTimeMillis());
        repository.saveTurn(legacyTurn);

        ConversationTurnListDTO response = service.listMessages(session.getSessionId(), 20, null);

        assertThat(response.getTurns()).hasSize(1);
        assertThat(response.getTurns().getFirst().getResultCards()).isEmpty();
    }

    private RewriteResult buildRewrite(String originalQuery, String rewrittenQuery, String reason, boolean fallback) {
        RewriteResult result = new RewriteResult();
        result.setOriginalQuery(originalQuery);
        result.setRewrittenQuery(rewrittenQuery);
        result.setRewriteReason(reason);
        result.setPreferredModalities(List.of("MIXED"));
        result.setTopicEntities(List.of("mysql", "innodb"));
        result.setConfidence(0.92d);
        result.setFallbackUsed(fallback);
        return result;
    }

    private ConversationRetrievalResult buildRetrievalResult(List<ConversationRetrievalCandidate> topCandidates) {
        ConversationRetrievalResult result = new ConversationRetrievalResult();
        result.setTopCandidates(topCandidates);
        ConversationRetrievalResult.GroupedResult groupedResult = new ConversationRetrievalResult.GroupedResult();
        groupedResult.setGroupKey("MIXED");
        groupedResult.setItems(topCandidates);
        result.setGroupedResults(topCandidates.isEmpty() ? List.of() : List.of(groupedResult));
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

    private ConversationMessageRequestDTO buildMessageRequest(String query) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery(query);
        request.setTopK(60);
        request.setLimit(20);
        request.setStrategy("KB_RRF_RERANK");
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
    }
}
