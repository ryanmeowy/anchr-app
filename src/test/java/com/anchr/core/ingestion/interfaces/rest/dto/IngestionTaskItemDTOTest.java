package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskItemDTOTest {

    @Test
    void from_shouldPreserveCompatibilityProjectionSequences() {
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.REEMBED),
                "EMBED", "PENDING", 60);
        assertProjection(new IngestionPublicProjection(
                        com.anchr.core.ingestion.domain.model.IngestionStage.PARSE,
                        com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus.RUNNING, 60),
                "PARSE", "RUNNING", 60);
        assertProjection(
                IngestionPublicProjectionPolicy.explicitRetry(),
                "UPLOAD", "PENDING", 0);
        assertProjection(new IngestionPublicProjection(
                        com.anchr.core.ingestion.domain.model.IngestionStage.PARSE,
                        com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus.RUNNING, 20),
                "PARSE", "RUNNING", 20);
        assertProjection(
                IngestionPublicProjectionPolicy.preflightFailure(),
                "UPLOAD", "FAILED", 0);
        assertProjection(
                IngestionPublicProjectionPolicy.skipped(),
                "ASKABLE", "SKIPPED", 100);
        assertProjection(
                IngestionPublicProjectionPolicy.success(),
                "ASKABLE", "SUCCESS", 100);
    }

    @Test
    void json_shouldKeepTheExistingPublicFieldNames() {
        IngestionTaskItemDTO dto = IngestionTaskItemDTO.from(item(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.UPLOAD)));

        JsonNode json = new ObjectMapper().valueToTree(dto);

        assertThat(json.properties().stream().map(entry -> entry.getKey()).toList())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "itemId", "assetId", "fileName", "fileHash",
                        "stage", "status", "progress", "dedupeStrategy", "dedupeResult",
                        "duplicateAssetId", "errorCode", "errorMessage",
                        "updatedAt", "finishedAt"));
    }

    private void assertProjection(
            IngestionPublicProjection projection,
            String stage,
            String status,
            int progress) {
        IngestionTaskItemDTO dto = IngestionTaskItemDTO.from(item(projection));
        assertThat(dto.getStage()).isEqualTo(stage);
        assertThat(dto.getStatus()).isEqualTo(status);
        assertThat(dto.getProgress()).isEqualTo(progress);
    }

    private IngestionTaskItem item(IngestionPublicProjection projection) {
        return IngestionTaskItem.builder()
                .id("item-1")
                .assetId("asset-1")
                .fileName("sample.pdf")
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .build();
    }
}
