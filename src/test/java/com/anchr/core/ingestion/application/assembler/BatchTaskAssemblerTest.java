package com.anchr.core.ingestion.application.assembler;

import com.anchr.core.ingestion.domain.model.BatchTask;
import com.anchr.core.ingestion.domain.model.BatchTaskItem;
import com.anchr.core.ingestion.domain.model.BatchTaskItemStatus;
import com.anchr.core.ingestion.domain.model.BatchTaskStatus;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchProcessDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchTaskAssemblerTest {

    private final BatchTaskAssembler assembler = new BatchTaskAssembler();

    @Test
    void toTaskDto_and_toTaskDomain_shouldRoundTripCoreFields() {
        BatchTask task = new BatchTask();
        task.setTaskId("task-1");
        task.setStatus(BatchTaskStatus.PARTIAL_FAILED);
        task.setTotal(2);
        task.setSuccessCount(1);
        task.setFailureCount(1);
        task.setRunningCount(0);
        task.setPendingCount(0);
        task.setCreatedAt(100L);
        task.setUpdatedAt(200L);
        task.setCompletedAt(300L);

        BatchTaskItem item = new BatchTaskItem();
        item.setItemId("item-1");
        item.setKey("k1");
        item.setFileName("f1.png");
        item.setFileHash("h1");
        item.setStatus(BatchTaskItemStatus.FAILED);
        item.setErrorMessage("boom");
        item.setRetryCount(2);
        item.setUpdatedAt(210L);
        task.setItems(List.of(item));

        BatchTaskStatusDTO dto = assembler.toTaskDto(task);
        BatchTask mapped = assembler.toTaskDomain(dto);

        assertThat(dto.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(dto.getItems()).hasSize(1);
        assertThat(mapped.getTaskId()).isEqualTo("task-1");
        assertThat(mapped.getStatus()).isEqualTo(BatchTaskStatus.PARTIAL_FAILED);
        assertThat(mapped.getFailureCount()).isEqualTo(1);
        assertThat(mapped.getItems().getFirst().getStatus()).isEqualTo(BatchTaskItemStatus.FAILED);
        assertThat(mapped.getItems().getFirst().getRetryCount()).isEqualTo(2);
    }

    @Test
    void toTaskDomain_shouldFallbackToPendingForInvalidStatus() {
        BatchTaskStatusDTO dto = new BatchTaskStatusDTO();
        dto.setTaskId("task-2");
        dto.setStatus("INVALID");

        BatchTaskStatusDTO.ItemStatus item = new BatchTaskStatusDTO.ItemStatus();
        item.setItemId("item-2");
        item.setStatus("UNKNOWN");
        dto.setItems(List.of(item));

        BatchTask task = assembler.toTaskDomain(dto);

        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.PENDING);
        assertThat(task.getItems().getFirst().getStatus()).isEqualTo(BatchTaskItemStatus.PENDING);
    }

    @Test
    void toBatchProcessDTO_shouldMapItemFields() {
        BatchTaskItem item = new BatchTaskItem();
        item.setKey("oss/key");
        item.setFileName("file.png");
        item.setFileHash("hash");

        BatchProcessDTO request = assembler.toBatchProcessDTO(item);

        assertThat(request.getKey()).isEqualTo("oss/key");
        assertThat(request.getFileName()).isEqualTo("file.png");
        assertThat(request.getFileHash()).isEqualTo("hash");
    }
}
