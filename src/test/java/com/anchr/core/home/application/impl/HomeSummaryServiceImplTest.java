package com.anchr.core.home.application.impl;

import com.anchr.core.activity.application.ActivityQueryService;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.home.interfaces.rest.dto.HomeSummaryDTO;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.model.ingestion.IngestionSourceType;
import com.anchr.core.kb.domain.model.ingestion.IngestionTask;
import com.anchr.core.kb.domain.model.ingestion.IngestionTaskStatus;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.kb.domain.repository.ingestion.IngestionTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeSummaryServiceImplTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final IngestionTaskRepository ingestionTaskRepository = mock(IngestionTaskRepository.class);
    private final ActivityQueryService activityQueryService = mock(ActivityQueryService.class);
    private final HomeSummaryServiceImpl service =
            new HomeSummaryServiceImpl(knowledgeBaseRepository, ingestionTaskRepository, activityQueryService);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void summary_shouldReturnHomeBlocksAndStableState() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        when(knowledgeBaseRepository.listActive("ws-a", 6, 0)).thenReturn(List.of(kb()));
        when(ingestionTaskRepository.listRecent("ws-a", 5)).thenReturn(List.of(task()));
        when(activityQueryService.recentQuestions(10, null)).thenReturn(RecentQuestionListDTO.builder()
                .items(List.of())
                .build());
        when(activityQueryService.recentCitations(10, null)).thenReturn(RecentCitationListDTO.builder()
                .items(List.of())
                .build());

        HomeSummaryDTO summary = service.summary();

        assertThat(summary.getFavoriteKbs()).hasSize(1);
        assertThat(summary.getFavoriteKbs().getFirst().getKbId()).isEqualTo("kb-1");
        assertThat(summary.getRecentIngestionTasks()).hasSize(1);
        assertThat(summary.getRecentIngestionTasks().getFirst().getTaskId()).isEqualTo("task-1");
        assertThat(summary.getWarnings()).isEmpty();
        assertThat(summary.getState().isLoading()).isFalse();
        assertThat(summary.getState().isEmpty()).isFalse();
        assertThat(summary.getState().isError()).isFalse();
    }

    private KnowledgeBase kb() {
        LocalDateTime now = LocalDateTime.now();
        return KnowledgeBase.builder()
                .id("kb-1")
                .workspaceId("ws-a")
                .name("产品知识库")
                .status(KnowledgeBaseStatus.ACTIVE)
                .documentCount(2)
                .segmentCount(30)
                .createdBy("user-a")
                .updatedBy("user-a")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private IngestionTask task() {
        LocalDateTime now = LocalDateTime.now();
        return IngestionTask.builder()
                .id("task-1")
                .workspaceId("ws-a")
                .kbId("kb-1")
                .sourceType(IngestionSourceType.UPLOAD)
                .status(IngestionTaskStatus.RUNNING)
                .totalCount(2)
                .successCount(1)
                .failureCount(0)
                .runningCount(1)
                .createdBy("user-a")
                .updatedBy("user-a")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
