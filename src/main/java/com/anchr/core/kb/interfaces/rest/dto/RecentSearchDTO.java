package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recent search item.
 */
@Value
@Builder
public class RecentSearchDTO {

    String query;
    List<String> kbIds;
    List<String> knowledgeBaseNames;
    int total;
    LocalDateTime searchedAt;
    List<String> assetTypes;
    DateRange dateRange;
    Boolean withAnswer;
    String answerMode;

    @Value
    @Builder
    public static class DateRange {
        Long from;
        Long to;
    }
}
