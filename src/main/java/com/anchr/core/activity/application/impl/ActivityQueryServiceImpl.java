package com.anchr.core.activity.application.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.activity.application.ActivityQueryService;
import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;
import com.anchr.core.activity.domain.repository.ActivityEventRepository;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default recent activity query service.
 */
@Service
@RequiredArgsConstructor
public class ActivityQueryServiceImpl implements ActivityQueryService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ActivityEventRepository activityEventRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;

    @Override
    public RecentQuestionListDTO recentQuestions(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.QUESTION_ASKED, boundedLimit + 1, offset);
        List<ActivityEvent> pageEvents = events.stream()
                .limit(boundedLimit)
                .toList();
        Map<String, String> knowledgeBaseNamesById = loadKnowledgeBaseNamesById(pageEvents);
        List<RecentQuestionDTO> items = pageEvents.stream()
                .map(event -> toRecentQuestion(event, knowledgeBaseNamesById))
                .toList();
        return RecentQuestionListDTO.builder()
                .items(items)
                .nextCursor(events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null)
                .build();
    }

    @Override
    public RecentCitationListDTO recentCitations(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.CITATION_OPENED, boundedLimit + 1, offset);
        List<RecentCitationDTO> items = events.stream()
                .limit(boundedLimit)
                .map(this::toRecentCitation)
                .toList();
        return RecentCitationListDTO.builder()
                .items(items)
                .nextCursor(events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null)
                .build();
    }

    private List<ActivityEvent> listEvents(ActivityEventType eventType, int limit, int offset) {
        RequestUserContext context = UserContextHolder.get();
        return activityEventRepository.listByType(context.workspaceId(), context.userId(), eventType, limit, offset);
    }

    private RecentQuestionDTO toRecentQuestion(ActivityEvent event, Map<String, String> knowledgeBaseNamesById) {
        Map<String, Object> payload = parsePayload(event);
        List<String> kbScope = readStringList(payload, "kbScope");
        return RecentQuestionDTO.builder()
                .turnId(readString(payload, "turnId", event.getResourceId()))
                .sessionId(readString(payload, "sessionId", null))
                .question(readString(payload, "question", null))
                .kbScope(kbScope)
                .knowledgeBaseNames(kbScope.stream()
                        .map(knowledgeBaseNamesById::get)
                        .filter(StringUtils::hasText)
                        .toList())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private Map<String, String> loadKnowledgeBaseNamesById(List<ActivityEvent> events) {
        Set<String> kbIds = events.stream()
                .flatMap(event -> readStringList(parsePayload(event), "kbScope").stream())
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        RequestUserContext context = UserContextHolder.get();
        return knowledgeBaseRepository.listActiveByIds(context.workspaceId(), List.copyOf(kbIds)).stream()
                .filter(knowledgeBase -> StringUtils.hasText(knowledgeBase.getId())
                        && StringUtils.hasText(knowledgeBase.getName()))
                .collect(Collectors.toMap(
                        KnowledgeBase::getId,
                        KnowledgeBase::getName,
                        (first, second) -> first
                ));
    }

    private RecentCitationDTO toRecentCitation(ActivityEvent event) {
        Map<String, Object> payload = parsePayload(event);
        return RecentCitationDTO.builder()
                .segmentId(readString(payload, "segmentId", event.getResourceId()))
                .assetId(readString(payload, "assetId", null))
                .kbId(readString(payload, "kbId", null))
                .fileName(readString(payload, "fileName", null))
                .title(readString(payload, "title", null))
                .snippet(readString(payload, "snippet", null))
                .citationReason(readString(payload, "citationReason", null))
                .openedAt(event.getCreatedAt())
                .build();
    }

    private Map<String, Object> parsePayload(ActivityEvent event) {
        if (!StringUtils.hasText(event.getPayload())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String readString(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private List<String> readStringList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeOffset(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Math.max(0, Integer.parseInt(decoded));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "cursor is invalid.");
        }
    }
}
