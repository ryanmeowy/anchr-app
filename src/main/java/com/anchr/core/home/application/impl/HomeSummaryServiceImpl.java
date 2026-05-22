package com.anchr.core.home.application.impl;

import com.anchr.core.activity.application.ActivityQueryService;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionDTO;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.home.application.HomeSummaryService;
import com.anchr.core.home.interfaces.rest.dto.HomeHelpLinkDTO;
import com.anchr.core.home.interfaces.rest.dto.HomeKnowledgeBaseDTO;
import com.anchr.core.home.interfaces.rest.dto.HomeSummaryDTO;
import com.anchr.core.home.interfaces.rest.dto.HomeViewStateDTO;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.ingestion.IngestionTask;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.kb.domain.repository.ingestion.IngestionTaskRepository;
import com.anchr.core.kb.interfaces.rest.dto.ingestion.IngestionTaskSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default Ask First home summary service.
 */
@Service
@RequiredArgsConstructor
public class HomeSummaryServiceImpl implements HomeSummaryService {

    private static final int FAVORITE_KB_LIMIT = 6;
    private static final int RECENT_ACTIVITY_LIMIT = 10;
    private static final int RECENT_INGESTION_LIMIT = 5;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final ActivityQueryService activityQueryService;

    @Override
    public HomeSummaryDTO summary() {
        List<String> warnings = new ArrayList<>();
        List<HomeKnowledgeBaseDTO> favoriteKbs = loadFavoriteKbs(warnings);
        List<RecentQuestionDTO> recentQuestions = loadRecentQuestions(warnings);
        List<RecentCitationDTO> recentCitations = loadRecentCitations(warnings);
        List<IngestionTaskSummaryDTO> recentTasks = loadRecentTasks(warnings);
        boolean empty = favoriteKbs.isEmpty()
                && recentQuestions.isEmpty()
                && recentCitations.isEmpty()
                && recentTasks.isEmpty();
        return HomeSummaryDTO.builder()
                .favoriteKbs(favoriteKbs)
                .recentQuestions(recentQuestions)
                .recentCitations(recentCitations)
                .recentIngestionTasks(recentTasks)
                .helpLinks(List.of())
                .warnings(warnings)
                .state(HomeViewStateDTO.builder()
                        .loading(false)
                        .empty(empty)
                        .error(!warnings.isEmpty() && empty)
                        .build())
                .build();
    }

    private List<HomeKnowledgeBaseDTO> loadFavoriteKbs(List<String> warnings) {
        try {
            RequestUserContext context = UserContextHolder.get();
            return knowledgeBaseRepository.listActive(context.workspaceId(), FAVORITE_KB_LIMIT, 0).stream()
                    .map(this::toHomeKnowledgeBase)
                    .toList();
        } catch (Exception e) {
            warnings.add("favoriteKbs unavailable");
            return List.of();
        }
    }

    private List<RecentQuestionDTO> loadRecentQuestions(List<String> warnings) {
        try {
            return activityQueryService.recentQuestions(RECENT_ACTIVITY_LIMIT, null).getItems();
        } catch (Exception e) {
            warnings.add("recentQuestions unavailable");
            return List.of();
        }
    }

    private List<RecentCitationDTO> loadRecentCitations(List<String> warnings) {
        try {
            return activityQueryService.recentCitations(RECENT_ACTIVITY_LIMIT, null).getItems();
        } catch (Exception e) {
            warnings.add("recentCitations unavailable");
            return List.of();
        }
    }

    private List<IngestionTaskSummaryDTO> loadRecentTasks(List<String> warnings) {
        try {
            RequestUserContext context = UserContextHolder.get();
            return ingestionTaskRepository.listRecent(context.workspaceId(), RECENT_INGESTION_LIMIT).stream()
                    .map(this::toSummary)
                    .toList();
        } catch (Exception e) {
            warnings.add("recentIngestionTasks unavailable");
            return List.of();
        }
    }

    private HomeKnowledgeBaseDTO toHomeKnowledgeBase(KnowledgeBase knowledgeBase) {
        return HomeKnowledgeBaseDTO.builder()
                .kbId(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .documentCount(knowledgeBase.getDocumentCount())
                .segmentCount(knowledgeBase.getSegmentCount())
                .updatedAt(knowledgeBase.getUpdatedAt())
                .build();
    }

    private IngestionTaskSummaryDTO toSummary(IngestionTask task) {
        return IngestionTaskSummaryDTO.from(task);
    }
}
