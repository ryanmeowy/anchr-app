package com.anchr.core.kb.application;

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

    void recordSearchExecuted(String query, List<String> kbIds, int total);
}
