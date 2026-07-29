package com.anchr.core.activity.application.impl;

import com.anchr.core.activity.application.acl.ActivityKnowledgeAcl;
import com.anchr.core.activity.application.api.ActivityQueryApi;
import com.anchr.core.activity.application.api.model.ActivityAnchor;
import com.anchr.core.activity.application.api.model.ActivityCitationChunk;
import com.anchr.core.activity.application.api.model.ActivityQueryResult;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;
import com.anchr.core.activity.domain.repository.ActivityEventRepository;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Default recent Activity query implementation. */
@Service
@RequiredArgsConstructor
public class ActivityQueryServiceImpl implements ActivityQueryApi {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int DEDUP_BUFFER = 10;

    private final ActivityEventRepository activityEventRepository;
    private final ActivityKnowledgeAcl activityKnowledgeAcl;
    private final ObjectMapper objectMapper;

    @Override
    public ActivityQueryResult.Page<ActivityQueryResult.Question> recentQuestions(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.QUESTION_ASKED, boundedLimit + 1, offset);
        List<ActivityEvent> pageEvents = events.stream().limit(boundedLimit).toList();
        Map<String, String> names = loadNamesByListPayload(pageEvents, "kbScope");
        return new ActivityQueryResult.Page<>(
                pageEvents.stream().map(event -> toQuestion(event, names)).toList(),
                events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null);
    }

    @Override
    public ActivityQueryResult.Page<ActivityQueryResult.Citation> recentCitations(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.CITATION_OPENED, boundedLimit + 1, offset);
        Map<String, String> names = loadNamesByScalarPayload(events);
        return new ActivityQueryResult.Page<>(
                events.stream().limit(boundedLimit).map(event -> toCitation(event, names)).toList(),
                events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null);
    }

    @Override
    public ActivityQueryResult.Page<ActivityQueryResult.Search> recentSearch(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        int fetchSize = boundedLimit + 1 + DEDUP_BUFFER;
        List<ActivityEvent> rawEvents = listEvents(ActivityEventType.SEARCH_EXECUTED, fetchSize, offset);
        List<ActivityEvent> uniqueEvents = deduplicate(rawEvents);
        List<ActivityEvent> pageEvents = uniqueEvents.size() > boundedLimit
                ? uniqueEvents.subList(0, boundedLimit) : uniqueEvents;
        Map<String, String> names = loadNamesByListPayload(pageEvents, "kbIds");
        boolean hasNext = uniqueEvents.size() > boundedLimit || rawEvents.size() >= fetchSize;
        return new ActivityQueryResult.Page<>(
                pageEvents.stream().map(event -> toSearch(event, names)).toList(),
                hasNext ? encodeOffset(offset + rawEvents.size()) : null);
    }

    @Override
    public ActivityQueryResult.Page<ActivityQueryResult.Document> recentDocument(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        int offset = decodeOffset(cursor);
        List<ActivityEvent> events = listEvents(ActivityEventType.DOCUMENT_IMPORTED, boundedLimit + 1, offset);
        List<ActivityEvent> pageEvents = events.stream().limit(boundedLimit).toList();
        Map<String, String> names = loadNamesByScalarPayload(pageEvents);
        return new ActivityQueryResult.Page<>(
                pageEvents.stream().map(event -> toDocument(event, names)).toList(),
                events.size() > boundedLimit ? encodeOffset(offset + boundedLimit) : null);
    }

    @Override
    public Optional<ActivityQueryResult.Citation> findCitationById(String id) {
        ActivityEvent event = activityEventRepository.fetchByIdAndType(id, ActivityEventType.CITATION_OPENED);
        if (event == null) {
            return Optional.empty();
        }
        return Optional.of(toCitation(event, loadNamesByScalarPayload(List.of(event))));
    }

    private List<ActivityEvent> listEvents(ActivityEventType eventType, int limit, int offset) {
        RequestUserContext context = UserContextHolder.get();
        return activityEventRepository.listByType(
                context.userId(), eventType, limit, offset, LocalDateTime.now().minusWeeks(1));
    }

    private ActivityQueryResult.Question toQuestion(ActivityEvent event, Map<String, String> names) {
        Map<String, Object> payload = parsePayload(event);
        List<String> kbScope = readStringList(payload, "kbScope");
        return new ActivityQueryResult.Question(
                readString(payload, "turnId", event.getResourceId()),
                readString(payload, "sessionId", null),
                readString(payload, "question", null),
                kbScope,
                kbScope.stream().map(names::get).filter(StringUtils::hasText).toList(),
                event.getCreatedAt());
    }

    private ActivityQueryResult.Citation toCitation(ActivityEvent event, Map<String, String> names) {
        Map<String, Object> payload = parsePayload(event);
        String kbId = readString(payload, "kbId", "");
        return new ActivityQueryResult.Citation(
                event.getId(),
                readString(payload, "segmentId", event.getResourceId()),
                readString(payload, "assetId", null),
                kbId,
                names.get(kbId),
                readString(payload, "fileName", null),
                readString(payload, "title", null),
                readString(payload, "snippet", null),
                readString(payload, "citationReason", null),
                event.getCreatedAt(),
                readString(payload, "sourceType", null),
                readString(payload, "sourceId", null),
                readString(payload, "sessionId", null),
                readString(payload, "citationIndex", null),
                readString(payload, "question", null),
                readAnchor(payload, "anchor"),
                readChunks(payload, "chunks"));
    }

    private ActivityQueryResult.Search toSearch(ActivityEvent event, Map<String, String> names) {
        Map<String, Object> payload = parsePayload(event);
        List<String> kbIds = readStringList(payload, "kbIds");
        return new ActivityQueryResult.Search(
                readString(payload, "query", null),
                kbIds,
                kbIds.stream().map(names::get).filter(StringUtils::hasText).toList(),
                readInt(payload, "total", 0),
                event.getCreatedAt(),
                readStringList(payload, "assetTypes"),
                readDateRange(payload, "dateRange"),
                readBoolean(payload, "withAnswer"),
                readString(payload, "answerMode", null));
    }

    private ActivityQueryResult.Document toDocument(ActivityEvent event, Map<String, String> names) {
        Map<String, Object> payload = parsePayload(event);
        String kbId = readString(payload, "kbId", null);
        return new ActivityQueryResult.Document(
                readString(payload, "taskId", event.getResourceId()),
                kbId,
                StringUtils.hasText(kbId) ? names.get(kbId) : null,
                readString(payload, "status", null),
                readInt(payload, "totalCount", 0),
                readInt(payload, "successCount", 0),
                readInt(payload, "failureCount", 0),
                readInt(payload, "runningCount", 0),
                event.getCreatedAt());
    }

    private Map<String, String> loadNamesByListPayload(List<ActivityEvent> events, String key) {
        Set<String> ids = events.stream()
                .flatMap(event -> readStringList(parsePayload(event), key).stream())
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return activityKnowledgeAcl.findActiveNames(ids);
    }

    private Map<String, String> loadNamesByScalarPayload(List<ActivityEvent> events) {
        Set<String> ids = events.stream()
                .map(event -> readString(parsePayload(event), "kbId", null))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return activityKnowledgeAcl.findActiveNames(ids);
    }

    private Map<String, Object> parsePayload(ActivityEvent event) {
        if (!StringUtils.hasText(event.getPayload())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.getPayload(), new TypeReference<>() {
            });
        } catch (Exception ignored) {
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
        return values.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
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
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Boolean readBoolean(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof Boolean value ? value : null;
    }

    private ActivityAnchor readAnchor(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, ActivityAnchor.class);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<ActivityCitationChunk> readChunks(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(value, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ActivityCitationChunk.class));
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private ActivityRecordCommand.DateRange readDateRange(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Long from = toLong(map.get("from"));
        Long to = toLong(map.get("to"));
        return from == null && to == null ? null : new ActivityRecordCommand.DateRange(from, to);
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
        } catch (NumberFormatException ignored) {
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

    public List<ActivityEvent> deduplicate(List<ActivityEvent> events) {
        if (events == null || events.isEmpty()) {
            return events == null ? List.of() : events;
        }
        Map<String, ActivityEvent> keyToFirstSeen = new HashMap<>();
        List<ActivityEvent> result = new ArrayList<>();
        for (ActivityEvent event : events) {
            Map<String, Object> payload = parsePayload(event);
            String key = buildDedupKey(readString(payload, "query", ""), readStringList(payload, "kbIds"));
            ActivityEvent firstSeen = keyToFirstSeen.get(key);
            if (firstSeen != null && isWithinOneSecond(firstSeen.getCreatedAt(), event.getCreatedAt())) {
                continue;
            }
            keyToFirstSeen.put(key, event);
            result.add(event);
        }
        return result;
    }

    private static String buildDedupKey(String query, List<String> kbIds) {
        String sortedKbIds = kbIds.stream().filter(Objects::nonNull).sorted().collect(Collectors.joining(","));
        return query + "::" + sortedKbIds;
    }

    private static boolean isWithinOneSecond(LocalDateTime a, LocalDateTime b) {
        return Math.abs(ChronoUnit.MILLIS.between(a, b)) <= 10_000;
    }
}
