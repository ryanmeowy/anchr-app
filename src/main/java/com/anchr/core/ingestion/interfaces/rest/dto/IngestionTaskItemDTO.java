package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Ingestion task item response DTO.
 */
@Value
@Builder
public class IngestionTaskItemDTO {

    String itemId;
    String assetId;
    String fileName;
    String fileHash;
    String stage;
    String status;
    int progress;
    String dedupeStrategy;
    String dedupeResult;
    String duplicateAssetId;
    String errorCode;
    String errorMessage;
    LocalDateTime updatedAt;
    LocalDateTime finishedAt;

    public static IngestionTaskItemDTO from(IngestionTaskItem item) {
        return IngestionTaskItemDTO.builder()
                .itemId(item.getId())
                .assetId(item.getAssetId())
                .fileName(item.getFileName())
                .fileHash(item.getFileHash())
                .stage(item.getStage().name())
                .status(item.getStatus().name())
                .progress(item.getProgress())
                .dedupeStrategy(item.getDedupeStrategy() == null ? null : item.getDedupeStrategy().name())
                .dedupeResult(item.getDedupeResult() == null ? null : item.getDedupeResult().name())
                .duplicateAssetId(item.getDuplicateAssetId())
                .errorCode(item.getErrorCode())
                .errorMessage(item.getErrorMessage())
                .updatedAt(item.getUpdatedAt())
                .finishedAt(item.getFinishedAt())
                .build();
    }
}
