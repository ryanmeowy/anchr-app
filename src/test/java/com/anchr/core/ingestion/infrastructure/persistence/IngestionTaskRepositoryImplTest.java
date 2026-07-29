package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskRepositoryImplTest {

    @Mock private IngestionTaskMapper mapper;

    @Test
    void saveShouldPersistOnlyBusinessItemFields() {
        IngestionTaskRepositoryImpl repository = new IngestionTaskRepositoryImpl(mapper);

        repository.save(task());

        ArgumentCaptor<IngestionTaskItemRecord> captor =
                ArgumentCaptor.forClass(IngestionTaskItemRecord.class);
        verify(mapper).insertItem(captor.capture());
        IngestionTaskItemRecord record = captor.getValue();
        assertThat(record.getId()).isEqualTo("item-1");
        assertThat(record.getTargetIndexGeneration()).isEqualTo(2L);
        assertThat(record.getStage()).isEqualTo("UPLOAD");
        assertThat(record.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void claimPendingShouldRefreshTaskSummaryAfterAtomicClaim() {
        IngestionTaskMapper localMapper = mapper;
        IngestionTaskItemRecord claimed = record();
        claimed.setStatus("RUNNING");
        claimed.setStage("PARSE");
        when(localMapper.claimPending("item-1")).thenReturn(1);
        when(localMapper.findRunningItem("item-1")).thenReturn(Optional.of(claimed));

        Optional<IngestionTaskItem> result =
                new IngestionTaskRepositoryImpl(localMapper).claimPending("item-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getStage()).isEqualTo(IngestionStage.PARSE);
        verify(localMapper).refreshSummary(
                "kb-1", "task-1", "user-1", claimed.getUpdatedAt());
    }

    @Test
    void taskShouldOwnTheSingleDedupeStrategy() {
        new IngestionTaskRepositoryImpl(mapper).save(task());

        ArgumentCaptor<IngestionTaskRecord> captor =
                ArgumentCaptor.forClass(IngestionTaskRecord.class);
        verify(mapper).insertTask(captor.capture());
        assertThat(captor.getValue().getDedupeStrategy()).isEqualTo("SKIP");
    }

    private IngestionTask task() {
        LocalDateTime now = LocalDateTime.now();
        return IngestionTask.builder()
                .id("task-1").kbId("kb-1").sourceType(IngestionSourceType.UPLOAD)
                .status(IngestionTaskStatus.PENDING).totalCount(1)
                .createdBy("user-1").updatedBy("user-1")
                .createdAt(now).updatedAt(now)
                .items(List.of(IngestionTaskItem.builder()
                        .id("item-1").taskId("task-1").kbId("kb-1")
                        .assetId("asset-1").targetIndexGeneration(2L)
                        .fileName("a.pdf").fileHash("hash")
                        .stage(IngestionStage.UPLOAD)
                        .status(IngestionTaskItemStatus.PENDING).progress(0)
                        .dedupeStrategy(DedupeStrategy.SKIP)
                        .createdAt(now).updatedAt(now).build()))
                .build();
    }

    private IngestionTaskItemRecord record() {
        IngestionTaskItemRecord record = new IngestionTaskItemRecord();
        record.setId("item-1");
        record.setTaskId("task-1");
        record.setKbId("kb-1");
        record.setTaskCreatedBy("user-1");
        record.setAssetId("asset-1");
        record.setTargetIndexGeneration(2L);
        record.setStage("UPLOAD");
        record.setStatus("PENDING");
        record.setProgress(0);
        record.setDedupeStrategy("SKIP");
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }
}
