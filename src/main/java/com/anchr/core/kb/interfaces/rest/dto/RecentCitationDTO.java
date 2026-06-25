package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Recent citation item.
 */
@Value
@Builder
public class RecentCitationDTO {

    String segmentId;
    String assetId;
    String kbId;
    String fileName;
    String title;
    String snippet;
    String citationReason;
    LocalDateTime openedAt;
}
