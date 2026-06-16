package com.anchr.core.kb.application;

import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;

import java.util.List;

/**
 * Application service for recording lightweight activity events.
 */
public interface ActivityEventService {

    void recordQuestionAsked(String sessionId, String turnId, String question, List<String> kbScope);

    void recordCitationOpened(String segmentId, String assetId, String kbId, String fileName,
                              String title, String snippet, String citationReason);

    void recordDocumentImported(String taskId, String kbId, String status,
                                int totalCount, int successCount, int failureCount, int runningCount);

    void recordSearchExecuted(SearchQueryDTO query, int total);
}
