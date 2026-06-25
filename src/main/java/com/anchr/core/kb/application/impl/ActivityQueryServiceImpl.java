package com.anchr.core.kb.application.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.application.ActivityQueryService;
import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentDocumentDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentSearchDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentSearchListDTO;
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

    @Override
    public RecentSearchListDTO recentSearch(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.SEARCH_EXECUTED, boundedLimit + 1, offset);
        List<ActivityEvent> pageEvents = events.stream()
                .limit(boundedLimit)
                .toList();
        Map<String, String> knowledgeBaseNamesById = loadKnowledgeBaseNamesByListPayload(pageEvents, "kbIds");
        List<RecentSearchDTO> items = pageEvents.stream()
                .map(event -> toRecentSearch(event, knowledgeBaseNamesById))
                .toList();
        return RecentSearchListDTO.builder()
                .items(items)
                .nextCursor(events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null)
                .build();
    }

    @Override
    public RecentDocumentListDTO recentDocument(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.DOCUMENT_IMPORTED, boundedLimit + 1, offset);
        List<ActivityEvent> pageEvents = events.stream()
                .limit(boundedLimit)
                .toList();
        Map<String, String> knowledgeBaseNamesById = loadKnowledgeBaseNamesByScalarPayload(pageEvents, "kbId");
        List<RecentDocumentDTO> items = pageEvents.stream()
                .map(event -> toRecentDocument(event, knowledgeBaseNamesById))
                .toList();
        return RecentDocumentListDTO.builder()
                .items(items)
                .nextCursor(events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null)
                .build();
    }

    private List<ActivityEvent> listEvents(ActivityEventType eventType, int limit, int offset) {
        RequestUserContext context = UserContextHolder.get();
        return activityEventRepository.listByType(context.userId(), eventType, limit, offset);
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
        return loadKnowledgeBaseNamesById(kbIds);
    }

    private Map<String, String> loadKnowledgeBaseNamesByListPayload(List<ActivityEvent> events, String listKey) {
        Set<String> kbIds = events.stream()
                .flatMap(event -> readStringList(parsePayload(event), listKey).stream())
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return loadKnowledgeBaseNamesById(kbIds);
    }

    private Map<String, String> loadKnowledgeBaseNamesByScalarPayload(List<ActivityEvent> events, String scalarKey) {
        Set<String> kbIds = events.stream()
                .map(event -> readString(parsePayload(event), scalarKey, null))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return loadKnowledgeBaseNamesById(kbIds);
    }

    private Map<String, String> loadKnowledgeBaseNamesById(Set<String> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeBaseRepository.listActiveByIds(List.copyOf(kbIds)).stream()
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

    private RecentSearchDTO toRecentSearch(ActivityEvent event, Map<String, String> knowledgeBaseNamesById) {
        Map<String, Object> payload = parsePayload(event);
        List<String> kbIds = readStringList(payload, "kbIds");
        return RecentSearchDTO.builder()
                .query(readString(payload, "query", null))
                .kbIds(kbIds)
                .knowledgeBaseNames(kbIds.stream()
                        .map(knowledgeBaseNamesById::get)
                        .filter(StringUtils::hasText)
                        .toList())
                .total(readInt(payload, "total", 0))
                .searchedAt(event.getCreatedAt())
                .assetTypes(readStringList(payload, "assetTypes"))
                .dateRange(readDateRange(payload, "dateRange"))
                .withAnswer(readBoolean(payload, "withAnswer"))
                .answerMode(readString(payload, "answerMode", null))
                .build();
    }

    private RecentDocumentDTO toRecentDocument(ActivityEvent event, Map<String, String> knowledgeBaseNamesById) {
        Map<String, Object> payload = parsePayload(event);
        String kbId = readString(payload, "kbId", null);
        return RecentDocumentDTO.builder()
                .taskId(readString(payload, "taskId", event.getResourceId()))
                .kbId(kbId)
                .knowledgeBaseName(StringUtils.hasText(kbId) ? knowledgeBaseNamesById.get(kbId) : null)
                .status(readString(payload, "status", null))
                .totalCount(readInt(payload, "totalCount", 0))
                .successCount(readInt(payload, "successCount", 0))
                .failureCount(readInt(payload, "failureCount", 0))
                .runningCount(readInt(payload, "runningCount", 0))
                .importedAt(event.getCreatedAt())
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

    private int readInt(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Boolean readBoolean(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private RecentSearchDTO.DateRange readDateRange(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Long from = toLong(map.get("from"));
        Long to = toLong(map.get("to"));
        if (from == null && to == null) {
            return null;
        }
        return RecentSearchDTO.DateRange.builder()
                .from(from)
                .to(to)
                .build();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
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
