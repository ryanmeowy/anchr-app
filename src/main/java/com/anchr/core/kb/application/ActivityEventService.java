package com.anchr.core.kb.application;

import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import lombok.Builder;

import java.util.List;

/**
 * Application service for recording lightweight activity events.
 */
public interface ActivityEventService {

    void recordQuestionAsked(String sessionId, String turnId, String question, List<String> kbScope);

    void recordCitationOpened(ActivityEventService.CitationContext cxt);

    void recordDocumentImported(String taskId, String kbId, String status,
                                int totalCount, int successCount, int failureCount, int runningCount);

    void recordSearchExecuted(SearchQueryDTO query, int total);

    void deleteBySessionId(String sessionId);

    @Builder
    record CitationContext(String segmentId, String assetId, String kbId, String fileName,
                           String title, String snippet, String citationReason, String citationIndex,
                           String question, String sourceType, String sourceId, String sessionId,
                           PreviewAnchorDTO anchor, List<CitationChunkSnapshotDTO> chunks){}
}
