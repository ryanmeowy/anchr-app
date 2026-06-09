package com.anchr.core.kb.activity.application.impl;

import com.anchr.core.kb.application.impl.ActivityQueryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        ActivityEvent first = event(ActivityEventType.QUESTION_ASKED, "turn-1",
                "{\"sessionId\":\"sess-1\",\"turnId\":\"turn-1\",\"question\":\"付款期限是什么？\",\"kbScope\":[\"kb-1\"]}");
        ActivityEvent second = event(ActivityEventType.QUESTION_ASKED, "turn-2",
                "{\"sessionId\":\"sess-2\",\"turnId\":\"turn-2\",\"question\":\"违约金怎么约定？\",\"kbScope\":[\"kb-2\"]}");
        when(activityEventRepository.listByType("ws-a", "user-a", ActivityEventType.QUESTION_ASKED, 2, 0))
                .thenReturn(List.of(first, second));
        when(knowledgeBaseRepository.listActiveByIds("ws-a", List.of("kb-1")))
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
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        ActivityEvent event = event(ActivityEventType.CITATION_OPENED, "seg-1",
                "{\"segmentId\":\"seg-1\",\"assetId\":\"doc-1\",\"kbId\":\"kb-1\","
                        + "\"fileName\":\"合同.pdf\",\"title\":\"合同\",\"snippet\":\"30日内付款\","
                        + "\"citationReason\":\"该片段包含付款期限。\"}");
        when(activityEventRepository.listByType("ws-a", "user-a", ActivityEventType.CITATION_OPENED, 11, 0))
                .thenReturn(List.of(event));

        RecentCitationListDTO result = service.recentCitations(10, null);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getSegmentId()).isEqualTo("seg-1");
        assertThat(result.getItems().getFirst().getFileName()).isEqualTo("合同.pdf");
        assertThat(result.getItems().getFirst().getCitationReason()).isEqualTo("该片段包含付款期限。");
        assertThat(result.getNextCursor()).isNull();
    }

    private ActivityEvent event(ActivityEventType eventType, String resourceId, String payload) {
        return ActivityEvent.builder()
                .id("act-" + resourceId)
                .workspaceId("ws-a")
                .userId("user-a")
                .eventType(eventType)
                .resourceType("TEST")
                .resourceId(resourceId)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private KnowledgeBase kb(String id, String name) {
        LocalDateTime now = LocalDateTime.now();
        return KnowledgeBase.builder()
                .id(id)
                .workspaceId("ws-a")
                .name(name)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
