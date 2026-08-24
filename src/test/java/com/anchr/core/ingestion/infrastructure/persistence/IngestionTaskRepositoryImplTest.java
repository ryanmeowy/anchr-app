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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
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

    @Test
    void findByIdShouldBatchLoadItemsAndUseParentTaskMetadata() {
        IngestionTaskRecord task = taskRecord("task-1", "kb-parent", "user-parent");
        IngestionTaskItemRecord item = itemRecord(
                "item-1", "task-1", "a.pdf", null, LocalDateTime.now());
        when(mapper.findTask("kb-parent", "task-1")).thenReturn(Optional.of(task));
        when(mapper.listItemsByTaskIds(List.of("task-1"))).thenReturn(List.of(item));

        IngestionTask result = new IngestionTaskRepositoryImpl(mapper)
                .findById("kb-parent", "task-1").orElseThrow();

        assertThat(result.getItems()).singleElement().satisfies(mapped -> {
            assertThat(mapped.getKbId()).isEqualTo("kb-parent");
            assertThat(mapped.getTaskCreatedBy()).isEqualTo("user-parent");
            assertThat(mapped.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        });
        verify(mapper).listItemsByTaskIds(List.of("task-1"));
    }

    @Test
    void findByClientRequestIdShouldBatchLoadItemsFromTheResolvedTask() {
        IngestionTaskRecord task = taskRecord("task-2", "kb-2", "user-2");
        task.setClientRequestId("request-2");
        IngestionTaskItemRecord item = itemRecord(
                "item-2", "task-2", "two.pdf", null, LocalDateTime.now());
        when(mapper.findTaskByClientRequestId("user-2", "request-2"))
                .thenReturn(Optional.of(task));
        when(mapper.listItemsByTaskIds(List.of("task-2"))).thenReturn(List.of(item));

        IngestionTask result = new IngestionTaskRepositoryImpl(mapper)
                .findByClientRequestId("user-2", "request-2").orElseThrow();

        assertThat(result.getId()).isEqualTo("task-2");
        assertThat(result.getItems()).singleElement().satisfies(mapped -> {
            assertThat(mapped.getKbId()).isEqualTo("kb-2");
            assertThat(mapped.getTaskCreatedBy()).isEqualTo("user-2");
            assertThat(mapped.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        });
        verify(mapper).listItemsByTaskIds(List.of("task-2"));
    }

    @Test
    void listShouldLoadAllItemsInOneBatchAndPreserveTaskAndItemOrder() {
        IngestionTaskRecord task2 = taskRecord("task-2", "kb-1", "user-2");
        IngestionTaskRecord task1 = taskRecord("task-1", "kb-1", "user-1");
        IngestionTaskRecord task3 = taskRecord("task-3", "kb-1", "user-3");
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItemRecord item1 = itemRecord(
                "item-1", "task-1", "one.pdf", null, now.minusSeconds(2));
        IngestionTaskItemRecord item2a = itemRecord(
                "item-2a", "task-2", "two-a.pdf", "first failure", now.minusSeconds(1));
        IngestionTaskItemRecord item2b = itemRecord(
                "item-2b", "task-2", "two-b.pdf", "second failure", now);
        when(mapper.listTasks("kb-1", "FAILED", 100))
                .thenReturn(List.of(task2, task1, task3));
        when(mapper.listItemsByTaskIds(List.of("task-2", "task-1", "task-3")))
                .thenReturn(List.of(item1, item2a, item2b));

        List<IngestionTask> result = new IngestionTaskRepositoryImpl(mapper)
                .list("kb-1", IngestionTaskStatus.FAILED, 100);

        assertThat(result).extracting(IngestionTask::getId)
                .containsExactly("task-2", "task-1", "task-3");
        assertThat(result.get(0).getItems()).extracting(IngestionTaskItem::getId)
                .containsExactly("item-2a", "item-2b");
        assertThat(result.get(1).getItems()).extracting(IngestionTaskItem::getId)
                .containsExactly("item-1");
        assertThat(result.get(2).getItems()).isEmpty();
        assertThat(result.get(0).getItems()).allSatisfy(item -> {
            assertThat(item.getKbId()).isEqualTo("kb-1");
            assertThat(item.getTaskCreatedBy()).isEqualTo("user-2");
            assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        });
        verify(mapper).listItemsByTaskIds(List.of("task-2", "task-1", "task-3"));
    }

    @Test
    void listShouldSkipItemQueryWhenNoTasksExist() {
        when(mapper.listTasks("kb-1", null, 20)).thenReturn(List.of());

        List<IngestionTask> result = new IngestionTaskRepositoryImpl(mapper)
                .list("kb-1", null, 20);

        assertThat(result).isEmpty();
        verify(mapper, never()).listItemsByTaskIds(anyList());
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

    private IngestionTaskRecord taskRecord(String id, String kbId, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskRecord record = new IngestionTaskRecord();
        record.setId(id);
        record.setKbId(kbId);
        record.setSourceType("UPLOAD");
        record.setDedupeStrategy("SKIP");
        record.setStatus("FAILED");
        record.setTotalCount(1);
        record.setSuccessCount(0);
        record.setFailureCount(1);
        record.setRunningCount(0);
        record.setCreatedBy(createdBy);
        record.setUpdatedBy(createdBy);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private IngestionTaskItemRecord itemRecord(String id, String taskId, String fileName,
                                               String errorMessage, LocalDateTime createdAt) {
        IngestionTaskItemRecord record = new IngestionTaskItemRecord();
        record.setId(id);
        record.setTaskId(taskId);
        record.setAssetId("asset-" + id);
        record.setFileName(fileName);
        record.setStage("PARSE");
        record.setStatus(errorMessage == null ? "SUCCESS" : "FAILED");
        record.setProgress(100);
        record.setErrorMessage(errorMessage);
        record.setCreatedAt(createdAt);
        record.setUpdatedAt(createdAt);
        return record;
    }
}
