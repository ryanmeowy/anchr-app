package com.anchr.core.home.interfaces.rest.dto;

import com.anchr.core.activity.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionDTO;
import com.anchr.core.kb.interfaces.rest.dto.ingestion.IngestionTaskSummaryDTO;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Ask First home summary response.
 */
@Value
@Builder
public class HomeSummaryDTO {

    List<HomeKnowledgeBaseDTO> favoriteKbs;
    List<RecentQuestionDTO> recentQuestions;
    List<RecentCitationDTO> recentCitations;
    List<IngestionTaskSummaryDTO> recentIngestionTasks;
    List<HomeHelpLinkDTO> helpLinks;
    List<String> warnings;
    HomeViewStateDTO state;
}
