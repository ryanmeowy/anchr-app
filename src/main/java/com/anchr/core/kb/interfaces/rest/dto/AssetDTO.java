package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Document asset response DTO.
 */
@Value
@Builder
public class AssetDTO {

    String id;
    String kbId;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    String fileHash;
    Integer versionNo;
    boolean previewAvailable;
    String parseStatus;
    String indexStatus;
    String availabilityStatus;
    int segmentCount;
    int indexedSegmentCount;
    String errorCode;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    /** Resolved server-side so the list can grey out delete on rows the caller may not remove. */
    boolean deletable;

    public static AssetDTO from(Asset document) {
        return AssetDTO.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .fileName(document.getFileName())
                .title(document.getTitle())
                .fileType(document.getFileType())
                .mimeType(document.getMimeType())
                .sizeBytes(document.getSizeBytes())
                .fileHash(document.getFileHash())
                .versionNo(document.getVersionNo())
                .previewAvailable(isPreviewAvailable(document))
                .parseStatus(document.getParseStatus().name())
                .indexStatus(document.getIndexStatus().name())
                .availabilityStatus(DocumentAvailabilityStatus.from(document).name())
                .segmentCount(document.getSegmentCount())
                .indexedSegmentCount(document.getIndexedSegmentCount())
                .errorCode(document.getErrorCode())
                .errorMessage(document.getErrorMessage())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .deletable(isDeletable(document))
                .build();
    }

    /**
     * The same rule the delete endpoint enforces: your own uploads, or anything if you are an
     * admin. Kept on the server so the button and the API can never disagree.
     */
    private static boolean isDeletable(Asset document) {
        RequestUserContext context = UserContextHolder.get();
        return "ADMIN".equals(context.role())
                || context.userId().equals(document.getCreatedBy());
    }

    private static boolean isPreviewAvailable(Asset document) {
        return StringUtils.hasText(document.getPreviewObjectKey())
                || StringUtils.hasText(document.getObjectKey());
    }
}
