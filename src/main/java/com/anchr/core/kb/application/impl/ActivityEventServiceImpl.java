package com.anchr.core.kb.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Default activity event recorder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityEventServiceImpl implements ActivityEventService {

    private static final String EVENT_ID_PREFIX = "act";

    private final ActivityEventRepository activityEventRepository;
    private final PrefixedIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    @Override
    public void recordQuestionAsked(String sessionId, String turnId, String question, List<String> kbScope) {
        saveEvent(ActivityEventType.QUESTION_ASKED, "CONVERSATION_TURN", turnId, Map.of(
                "sessionId", valueOrEmpty(sessionId),
                "turnId", valueOrEmpty(turnId),
                "question", valueOrEmpty(question),
                "kbScope", kbScope == null ? List.of() : kbScope
        ));
    }

    @Override
    public void recordCitationOpened(String segmentId, String assetId, String kbId, String fileName,
                                     String title, String snippet, String citationReason) {
        saveEvent(ActivityEventType.CITATION_OPENED, "SEGMENT", segmentId, Map.of(
                "segmentId", valueOrEmpty(segmentId),
                "assetId", valueOrEmpty(assetId),
                "kbId", valueOrEmpty(kbId),
                "fileName", valueOrEmpty(fileName),
                "title", valueOrEmpty(title),
                "snippet", valueOrEmpty(snippet),
                "citationReason", valueOrEmpty(citationReason)
        ));
    }

    @Override
    public void recordDocumentImported(String taskId, String kbId, String status,
                                       int totalCount, int successCount, int failureCount, int runningCount) {
        saveEvent(ActivityEventType.DOCUMENT_IMPORTED, "INGESTION_TASK", taskId, Map.of(
                "taskId", valueOrEmpty(taskId),
                "kbId", valueOrEmpty(kbId),
                "status", valueOrEmpty(status),
                "totalCount", totalCount,
                "successCount", successCount,
                "failureCount", failureCount,
                "runningCount", runningCount
        ));
    }

    @Override
    public void recordSearchExecuted(String query, List<String> kbIds, int total) {
        saveEvent(ActivityEventType.SEARCH_EXECUTED, "SEARCH", null, Map.of(
                "query", valueOrEmpty(query),
                "kbIds", kbIds == null ? List.of() : kbIds,
                "total", total
        ));
    }

    private void saveEvent(ActivityEventType eventType, String resourceType, String resourceId, Map<String, Object> payload) {
        try {
            RequestUserContext context = UserContextHolder.get();
            ActivityEvent event = ActivityEvent.builder()
                    .id(idGenerator.nextId(EVENT_ID_PREFIX))
                    .workspaceId(context.workspaceId())
                    .userId(context.userId())
                    .eventType(eventType)
                    .resourceType(resourceType)
                    .resourceId(trimToNull(resourceId))
                    .payload(toJson(payload))
                    .createdAt(LocalDateTime.now())
                    .build();
            activityEventRepository.save(event);
        } catch (Exception e) {
            log.warn("activity event record failed, eventType={}", eventType, e);
        }
    }

    private String toJson(Map<String, Object> payload) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
