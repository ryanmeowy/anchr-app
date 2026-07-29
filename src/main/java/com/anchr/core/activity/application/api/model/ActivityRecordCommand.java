package com.anchr.core.activity.application.api.model;

import java.time.LocalDateTime;
import java.util.List;

/** Immutable commands accepted by the Activity provider. */
public final class ActivityRecordCommand {

    private ActivityRecordCommand() {
    }

    public record QuestionAsked(String userId, String sessionId, String turnId, String question,
                                List<String> kbScope, LocalDateTime occurredAt) {
        public QuestionAsked {
            kbScope = copy(kbScope);
        }
    }

    public record SearchExecuted(String userId, String query, List<String> kbIds, int total,
                                 List<String> assetTypes, DateRange dateRange, Boolean withAnswer,
                                 String answerMode, LocalDateTime occurredAt) {
        public SearchExecuted {
            kbIds = copy(kbIds);
            assetTypes = copy(assetTypes);
        }
    }

    public record DocumentImported(String userId, String taskId, String kbId, String status,
                                   int totalCount, int successCount, int failureCount, int runningCount,
                                   LocalDateTime occurredAt) {
    }

    public record CitationOpened(String userId, String segmentId, String assetId, String kbId,
                                 String fileName, String title, String snippet, String citationReason,
                                 String citationIndex, String question, String sourceType, String sourceId,
                                 String sessionId, ActivityAnchor anchor, List<ActivityCitationChunk> chunks,
                                 LocalDateTime occurredAt) {
        public CitationOpened {
            chunks = copy(chunks);
        }
    }

    public record DateRange(Long from, Long to) {
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
