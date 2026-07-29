package com.anchr.core.activity.application.api.model;

import java.time.LocalDateTime;
import java.util.List;

/** Immutable results published by Activity queries. */
public final class ActivityQueryResult {

    private ActivityQueryResult() {
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        }
    }

    public record Question(String turnId, String sessionId, String question, List<String> kbScope,
                           List<String> knowledgeBaseNames, LocalDateTime createdAt) {
        public Question {
            kbScope = copy(kbScope);
            knowledgeBaseNames = copy(knowledgeBaseNames);
        }
    }

    public record Citation(String recordId, String segmentId, String assetId, String kbId, String kbName,
                           String fileName, String title, String snippet, String citationReason,
                           LocalDateTime openedAt, String sourceType, String sourceId, String sessionId,
                           String citationIndex, String question, ActivityAnchor anchor,
                           List<ActivityCitationChunk> chunks) {
        public Citation {
            chunks = copy(chunks);
        }
    }

    public record Search(String query, List<String> kbIds, List<String> knowledgeBaseNames, int total,
                         LocalDateTime searchedAt, List<String> assetTypes,
                         ActivityRecordCommand.DateRange dateRange, Boolean withAnswer, String answerMode) {
        public Search {
            kbIds = copy(kbIds);
            knowledgeBaseNames = copy(knowledgeBaseNames);
            assetTypes = copy(assetTypes);
        }
    }

    public record Document(String taskId, String kbId, String knowledgeBaseName, String status,
                           int totalCount, int successCount, int failureCount, int runningCount,
                           LocalDateTime importedAt) {
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
