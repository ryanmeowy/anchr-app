package com.anchr.core.activity.application.impl;

import com.anchr.core.activity.application.api.ActivityRecordApi;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;
import com.anchr.core.activity.domain.repository.ActivityEventRepository;
import com.anchr.core.common.util.IdGen;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Default Activity write API implementation. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityRecordServiceImpl implements ActivityRecordApi {

    private final ActivityEventRepository activityEventRepository;
    private final IdGen idGen;
    private final ObjectMapper objectMapper;

    @Override
    public void recordQuestionAsked(ActivityRecordCommand.QuestionAsked command) {
        saveEvent(command.userId(), command.occurredAt(), ActivityEventType.QUESTION_ASKED,
                "CONVERSATION_TURN", command.turnId(), Map.of(
                        "sessionId", valueOrEmpty(command.sessionId()),
                        "turnId", valueOrEmpty(command.turnId()),
                        "question", valueOrEmpty(command.question()),
                        "kbScope", command.kbScope()));
    }

    @Override
    public void recordCitationOpened(ActivityRecordCommand.CitationOpened command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("segmentId", valueOrEmpty(command.segmentId()));
        payload.put("assetId", valueOrEmpty(command.assetId()));
        payload.put("kbId", valueOrEmpty(command.kbId()));
        payload.put("fileName", valueOrEmpty(command.fileName()));
        payload.put("title", valueOrEmpty(command.title()));
        payload.put("snippet", valueOrEmpty(command.snippet()));
        payload.put("citationReason", valueOrEmpty(command.citationReason()));
        payload.put("citationIndex", valueOrEmpty(command.citationIndex()));
        payload.put("sessionId", valueOrEmpty(command.sessionId()));
        payload.put("sourceId", valueOrEmpty(command.sourceId()));
        payload.put("sourceType", valueOrEmpty(command.sourceType()));
        payload.put("question", valueOrEmpty(command.question()));
        if (command.anchor() != null) {
            payload.put("anchor", command.anchor());
        }
        if (!command.chunks().isEmpty()) {
            payload.put("chunks", command.chunks());
        }
        saveEvent(command.userId(), command.occurredAt(), ActivityEventType.CITATION_OPENED,
                "SEGMENT", command.segmentId(), payload);
    }

    @Override
    public void recordDocumentImported(ActivityRecordCommand.DocumentImported command) {
        saveEvent(command.userId(), command.occurredAt(), ActivityEventType.DOCUMENT_IMPORTED,
                "INGESTION_TASK", command.taskId(), Map.of(
                        "taskId", valueOrEmpty(command.taskId()),
                        "kbId", valueOrEmpty(command.kbId()),
                        "status", valueOrEmpty(command.status()),
                        "totalCount", command.totalCount(),
                        "successCount", command.successCount(),
                        "failureCount", command.failureCount(),
                        "runningCount", command.runningCount()));
    }

    @Override
    public void recordSearchExecuted(ActivityRecordCommand.SearchExecuted command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", valueOrEmpty(command.query()));
        payload.put("kbIds", command.kbIds());
        payload.put("total", command.total());
        payload.put("assetTypes", command.assetTypes());
        if (command.dateRange() != null) {
            Map<String, Long> dateRange = new LinkedHashMap<>();
            dateRange.put("from", command.dateRange().from());
            dateRange.put("to", command.dateRange().to());
            payload.put("dateRange", dateRange);
        }
        payload.put("withAnswer", command.withAnswer());
        payload.put("answerMode", valueOrEmpty(command.answerMode()));
        saveEvent(command.userId(), command.occurredAt(), ActivityEventType.SEARCH_EXECUTED,
                "SEARCH", null, payload);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        activityEventRepository.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteCitationOpenedByAssetId(String userId, String assetId) {
        activityEventRepository.deleteCitationOpenedByAssetId(userId, assetId);
    }

    private void saveEvent(String userId, LocalDateTime occurredAt, ActivityEventType eventType,
                           String resourceType, String resourceId, Map<String, Object> payload) {
        try {
            ActivityEvent event = ActivityEvent.builder()
                    .id(idGen.nextIdStr())
                    .userId(userId)
                    .eventType(eventType)
                    .resourceType(resourceType)
                    .resourceId(trimToNull(resourceId))
                    .payload(toJson(payload))
                    .createdAt(occurredAt)
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
