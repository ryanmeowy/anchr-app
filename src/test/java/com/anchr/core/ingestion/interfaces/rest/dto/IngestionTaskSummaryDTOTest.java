package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskSummaryDTOTest {

    @Test
    void summaryShouldKeepTheFirstNonBlankItemFailureReason() {
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .sourceType(IngestionSourceType.UPLOAD)
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(
                        item("ignored.pdf", "  "),
                        item("first.pdf", " first failure "),
                        item("second.pdf", "second failure")))
                .build();

        IngestionTaskSummaryDTO summary = IngestionTaskSummaryDTO.from(task);

        assertThat(summary.getFailureReason()).isEqualTo("first.pdf: first failure");
    }

    private IngestionTaskItem item(String fileName, String errorMessage) {
        return IngestionTaskItem.builder()
                .id(fileName)
                .taskId("task-1")
                .fileName(fileName)
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }
}
