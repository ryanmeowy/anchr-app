package com.anchr.core.kb.interfaces.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Recent citation item.
 */
@Value
@Builder
public class RecentCitationDTO {

    String recordId;
    String segmentId;
    String assetId;
    String kbId;
    String kbName;
    String fileName;
    String title;
    String snippet;
    String citationReason;
    LocalDateTime openedAt;
    String sourceType;
    String sourceId;
    String sessionId;
    String citationIndex;
    String question;
    String why;
}
