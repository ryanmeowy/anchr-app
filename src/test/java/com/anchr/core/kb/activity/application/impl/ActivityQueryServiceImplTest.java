package com.anchr.core.kb.activity.application.impl;

import com.anchr.core.kb.application.impl.ActivityQueryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentSearchListDTO;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityQueryServiceImplTest {

    private final ActivityEventRepository activityEventRepository = mock(ActivityEventRepository.class);
    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final ActivityQueryServiceImpl service =
            new ActivityQueryServiceImpl(activityEventRepository, knowledgeBaseRepository, new ObjectMapper());

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void recentQuestions_shouldMapPayloadAndBuildNextCursor() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        ActivityEvent first = event(ActivityEventType.QUESTION_ASKED, "turn-1",
                "{\"sessionId\":\"sess-1\",\"turnId\":\"turn-1\",\"question\":\"付款期限是什么？\",\"kbScope\":[\"kb-1\"]}");
        ActivityEvent second = event(ActivityEventType.QUESTION_ASKED, "turn-2",
                "{\"sessionId\":\"sess-2\",\"turnId\":\"turn-2\",\"question\":\"违约金怎么约定？\",\"kbScope\":[\"kb-2\"]}");
        when(activityEventRepository.listByType("user-a", ActivityEventType.QUESTION_ASKED, 2, 0))
                .thenReturn(List.of(first, second));
        when(knowledgeBaseRepository.listActiveByIds(List.of("kb-1")))
                .thenReturn(List.of(kb("kb-1", "合同知识库")));

        RecentQuestionListDTO result = service.recentQuestions(1, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTurnId()).isEqualTo("turn-1");
        assertThat(result.getItems().getFirst().getQuestion()).isEqualTo("付款期限是什么？");
        assertThat(result.getItems().getFirst().getKbScope()).containsExactly("kb-1");
        assertThat(result.getItems().getFirst().getKnowledgeBaseNames()).containsExactly("合同知识库");
        assertThat(result.getNextCursor()).isNotBlank();
    }

    @Test
    void recentCitations_shouldMapCitationPayload() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        ActivityEvent event = event(ActivityEventType.CITATION_OPENED, "seg-1",
                "{\"segmentId\":\"seg-1\",\"assetId\":\"doc-1\",\"kbId\":\"kb-1\","
                        + "\"fileName\":\"合同.pdf\",\"title\":\"合同\",\"snippet\":\"30日内付款\","
                        + "\"citationReason\":\"该片段包含付款期限。\"}");
        when(activityEventRepository.listByType("user-a", ActivityEventType.CITATION_OPENED, 11, 0))
                .thenReturn(List.of(event));

        RecentCitationListDTO result = service.recentCitations(10, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getSegmentId()).isEqualTo("seg-1");
        assertThat(result.getItems().getFirst().getFileName()).isEqualTo("合同.pdf");
        assertThat(result.getItems().getFirst().getCitationReason()).isEqualTo("该片段包含付款期限。");
        assertThat(result.getNextCursor()).isNull();
    }

    @Test
    void recentSearch_shouldMapSearchPayload() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        ActivityEvent event = event(ActivityEventType.SEARCH_EXECUTED, null,
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"],\"total\":12,"
                        + "\"assetTypes\":[\"PDF\",\"IMAGE\"],"
                        + "\"dateRange\":{\"from\":1715678900,\"to\":1715765300},"
                        + "\"withAnswer\":true,"
                        + "\"answerMode\":\"BRIEF\"}");
        when(activityEventRepository.listByType("user-a", ActivityEventType.SEARCH_EXECUTED, 21, 0))
                .thenReturn(List.of(event));
        when(knowledgeBaseRepository.listActiveByIds(List.of("kb-1", "kb-2")))
                .thenReturn(List.of(kb("kb-1", "合同知识库"), kb("kb-2", "制度知识库")));

        RecentSearchListDTO result = service.recentSearch(10, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getQuery()).isEqualTo("付款期限");
        assertThat(result.getItems().getFirst().getKbIds()).containsExactly("kb-1", "kb-2");
        assertThat(result.getItems().getFirst().getKnowledgeBaseNames()).containsExactly("合同知识库", "制度知识库");
        assertThat(result.getItems().getFirst().getTotal()).isEqualTo(12);
        assertThat(result.getItems().getFirst().getAssetTypes()).containsExactly("PDF", "IMAGE");
        assertThat(result.getItems().getFirst().getDateRange()).isNotNull();
        assertThat(result.getItems().getFirst().getDateRange().getFrom()).isEqualTo(1715678900L);
        assertThat(result.getItems().getFirst().getDateRange().getTo()).isEqualTo(1715765300L);
        assertThat(result.getItems().getFirst().getWithAnswer()).isTrue();
        assertThat(result.getItems().getFirst().getAnswerMode()).isEqualTo("BRIEF");
        assertThat(result.getNextCursor()).isNull();
    }

    @Test
    void recentDocument_shouldMapDocumentImportedPayload() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        ActivityEvent event = event(ActivityEventType.DOCUMENT_IMPORTED, "task-1",
                "{\"taskId\":\"task-1\",\"kbId\":\"kb-1\",\"status\":\"COMPLETED\","
                        + "\"totalCount\":3,\"successCount\":2,\"failureCount\":1,\"runningCount\":0}");
        when(activityEventRepository.listByType("user-a", ActivityEventType.DOCUMENT_IMPORTED, 11, 0))
                .thenReturn(List.of(event));
        when(knowledgeBaseRepository.listActiveByIds(List.of("kb-1")))
                .thenReturn(List.of(kb("kb-1", "合同知识库")));

        RecentDocumentListDTO result = service.recentDocument(10, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTaskId()).isEqualTo("task-1");
        assertThat(result.getItems().getFirst().getKbId()).isEqualTo("kb-1");
        assertThat(result.getItems().getFirst().getKnowledgeBaseName()).isEqualTo("合同知识库");
        assertThat(result.getItems().getFirst().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getItems().getFirst().getTotalCount()).isEqualTo(3);
        assertThat(result.getItems().getFirst().getSuccessCount()).isEqualTo(2);
        assertThat(result.getItems().getFirst().getFailureCount()).isEqualTo(1);
        assertThat(result.getItems().getFirst().getRunningCount()).isZero();
        assertThat(result.getNextCursor()).isNull();
    }

    private ActivityEvent event(ActivityEventType eventType, String resourceId, String payload) {
        return ActivityEvent.builder()
                .id("act-" + resourceId)
                .userId("user-a")
                .eventType(eventType)
                .resourceType("TEST")
                .resourceId(resourceId)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ActivityEvent event(ActivityEventType eventType, String resourceId, String payload, LocalDateTime createdAt) {
        return ActivityEvent.builder()
                .id("act-" + resourceId)
                .userId("user-a")
                .eventType(eventType)
                .resourceType("TEST")
                .resourceId(resourceId)
                .payload(payload)
                .createdAt(createdAt)
                .build();
    }

    private KnowledgeBase kb(String id, String name) {
        LocalDateTime now = LocalDateTime.now();
        return KnowledgeBase.builder()
                .id(id)
                .name(name)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ── deduplicate() direct tests ──

    @Test
    void deduplicate_noDuplicates_returnsAll() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"a\",\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"b\",\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent e3 = event(ActivityEventType.SEARCH_EXECUTED, "3",
                "{\"query\":\"a\",\"kbIds\":[\"kb-2\"]}", now);

        List<ActivityEvent> result = service.deduplicate(List.of(e1, e2, e3));

        assertThat(result).hasSize(3);
    }

    @Test
    void deduplicate_sameQueryKbIdsWithinOneSecond_removesDuplicate() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent first = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"]}", now);
        ActivityEvent duplicate = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"]}", now.plus(500, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(first, duplicate));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo("act-1");
    }

    @Test
    void deduplicate_sameQueryKbIdsFarApart_keepsBoth() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent first = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"]}", now);
        ActivityEvent later = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"]}", now.plus(2, ChronoUnit.SECONDS));

        List<ActivityEvent> result = service.deduplicate(List.of(first, later));

        assertThat(result).hasSize(2);
    }

    @Test
    void deduplicate_sameKbIdsDifferentOrder_treatedAsDuplicate() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent first = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\",\"kb-2\"]}", now);
        ActivityEvent reversed = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-2\",\"kb-1\"]}", now.plus(200, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(first, reversed));

        assertThat(result).hasSize(1);
    }

    @Test
    void deduplicate_sameQueryDifferentKbIds_keepsBoth() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"付款期限\",\"kbIds\":[\"kb-2\"]}", now.plus(100, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(e1, e2));

        assertThat(result).hasSize(2);
    }

    @Test
    void deduplicate_nullQuery_handledGracefully() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"kbIds\":[\"kb-1\"]}", now.plus(300, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(e1, e2));

        assertThat(result).hasSize(1);
    }

    @Test
    void deduplicate_emptyKbIds_handledGracefully() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"hello\"}", now);
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"hello\",\"kbIds\":[]}", now.plus(400, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(e1, e2));

        assertThat(result).hasSize(1);
    }

    @Test
    void deduplicate_emptyList_returnsEmpty() {
        assertThat(service.deduplicate(List.of())).isEmpty();
    }

    @Test
    void deduplicate_multipleDuplicatesWithinWindow_keepsFirstOnly() {
        LocalDateTime now = LocalDateTime.now();
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"test\",\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"test\",\"kbIds\":[\"kb-1\"]}", now.plus(200, ChronoUnit.MILLIS));
        ActivityEvent e3 = event(ActivityEventType.SEARCH_EXECUTED, "3",
                "{\"query\":\"test\",\"kbIds\":[\"kb-1\"]}", now.plus(900, ChronoUnit.MILLIS));

        List<ActivityEvent> result = service.deduplicate(List.of(e1, e2, e3));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo("act-1");
    }

    // ── recentSearch() integration test with duplicates ──

    @Test
    void recentSearch_withDuplicates_returnsCorrectPage() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        LocalDateTime now = LocalDateTime.now();

        // 4 unique search concepts, mixed with duplicates within 1s
        ActivityEvent e1 = event(ActivityEventType.SEARCH_EXECUTED, "1",
                "{\"query\":\"a\",\"kbIds\":[\"kb-1\"]}", now);
        ActivityEvent dup1 = event(ActivityEventType.SEARCH_EXECUTED, "dup1",
                "{\"query\":\"a\",\"kbIds\":[\"kb-1\"]}", now.plus(200, ChronoUnit.MILLIS));
        ActivityEvent e2 = event(ActivityEventType.SEARCH_EXECUTED, "2",
                "{\"query\":\"b\",\"kbIds\":[\"kb-1\"]}", now.plus(500, ChronoUnit.MILLIS));
        ActivityEvent e3 = event(ActivityEventType.SEARCH_EXECUTED, "3",
                "{\"query\":\"c\",\"kbIds\":[\"kb-2\"]}", now.plus(800, ChronoUnit.MILLIS));
        ActivityEvent dup2 = event(ActivityEventType.SEARCH_EXECUTED, "dup2",
                "{\"query\":\"c\",\"kbIds\":[\"kb-2\"]}", now.plus(1000, ChronoUnit.MILLIS));
        ActivityEvent e4 = event(ActivityEventType.SEARCH_EXECUTED, "4",
                "{\"query\":\"d\",\"kbIds\":[\"kb-1\"]}", now.plus(1200, ChronoUnit.MILLIS));

        when(activityEventRepository.listByType("user-a", ActivityEventType.SEARCH_EXECUTED, 14, 0))
                .thenReturn(List.of(e1, dup1, e2, e3, dup2, e4));
        when(knowledgeBaseRepository.listActiveByIds(List.of("kb-1", "kb-2")))
                .thenReturn(List.of(kb("kb-1", "KB1"), kb("kb-2", "KB2")));

        RecentSearchListDTO result = service.recentSearch(3, null);

        // 4 unique after dedup, page = first 3
        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems().get(0).getQuery()).isEqualTo("a");
        assertThat(result.getItems().get(1).getQuery()).isEqualTo("b");
        assertThat(result.getItems().get(2).getQuery()).isEqualTo("c");
        // hasNext: uniqueEvents.size() (4) > boundedLimit (3)
        assertThat(result.getNextCursor()).isNotBlank();
    }

    @Test
    void recentSearch_hasNextTrueWhenRawFetchFull() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        LocalDateTime now = LocalDateTime.now();

        // limit=1, fetchSize=12. Create 12 events: 11 with query "a", 1 with query "b"
        // After dedup: 2 unique. hasNext=true because unique=2 > boundedLimit(1)
        List<ActivityEvent> raw = new ArrayList<>();
        raw.add(event(ActivityEventType.SEARCH_EXECUTED, "first-a",
                "{\"query\":\"a\",\"kbIds\":[\"kb-1\"]}", now));
        for (int i = 0; i < 10; i++) {
            raw.add(event(ActivityEventType.SEARCH_EXECUTED, "dup-a-" + i,
                    "{\"query\":\"a\",\"kbIds\":[\"kb-1\"]}", now.plus(100 + i, ChronoUnit.MILLIS)));
        }
        raw.add(event(ActivityEventType.SEARCH_EXECUTED, "unique-b",
                "{\"query\":\"b\",\"kbIds\":[\"kb-1\"]}", now.plus(2000, ChronoUnit.MILLIS)));

        when(activityEventRepository.listByType("user-a", ActivityEventType.SEARCH_EXECUTED, 12, 0))
                .thenReturn(raw);
        when(knowledgeBaseRepository.listActiveByIds(List.of("kb-1")))
                .thenReturn(List.of(kb("kb-1", "KB1")));

        RecentSearchListDTO result = service.recentSearch(1, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getQuery()).isEqualTo("a");
        assertThat(result.getNextCursor()).isNotBlank();
    }
}
