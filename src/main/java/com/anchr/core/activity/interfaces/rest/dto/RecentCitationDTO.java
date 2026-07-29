package com.anchr.core.activity.interfaces.rest.dto;

import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

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
    PreviewAnchorDTO anchor;
    List<CitationChunkSnapshotDTO> chunks;
}
