package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionExecutionKind;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionRetryConflictException;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskRepositoryImplTest {

    @Mock
    private IngestionTaskMapper mapper;

    @Test
    void freshReembed_shouldPersistParseAttemptAndExecutionWithoutInventingArtifact() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = baseItem(now)
                .stage(IngestionStage.EMBED)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(60)
                .dedupeStrategy(DedupeStrategy.SKIP)
                .build();
        stubGeneratedIds(101L, 201L);
        when(mapper.pointItemToExecution("item-1", 201L, now)).thenReturn(1);

        new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.REEMBED, List.of(item), now));

        ArgumentCaptor<IngestionTaskRecord> taskRecord =
                ArgumentCaptor.forClass(IngestionTaskRecord.class);
        ArgumentCaptor<IngestionTaskItemRecord> itemRecord =
                ArgumentCaptor.forClass(IngestionTaskItemRecord.class);
        ArgumentCaptor<IngestionParseAttemptRecord> parseAttempt =
                ArgumentCaptor.forClass(IngestionParseAttemptRecord.class);
        ArgumentCaptor<IngestionExecutionRecord> execution =
                ArgumentCaptor.forClass(IngestionExecutionRecord.class);
        verify(mapper).insertTask(taskRecord.capture());
        verify(mapper).insertItem(itemRecord.capture());
        verify(mapper).insertParseAttempt(parseAttempt.capture());
        verify(mapper).insertExecution(execution.capture());
        verify(mapper).pointItemToExecution("item-1", 201L, now);
        verify(mapper, never()).insertArtifact(any());

        assertThat(taskRecord.getValue().getDedupeStrategy()).isEqualTo("SKIP");
        assertThat(itemRecord.getValue().getCurrentExecutionId()).isNull();
        assertThat(parseAttempt.getValue().getId()).isEqualTo(101L);
        assertThat(parseAttempt.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(execution.getValue().getId()).isEqualTo(201L);
        assertThat(execution.getValue().getParseAttemptId()).isEqualTo(101L);
        assertThat(execution.getValue().getExecutionKind()).isEqualTo("REEMBED");
        assertThat(execution.getValue().getPhase())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT.name());
        assertThat(execution.getValue().getExecutionStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void explicitInitialIntent_shouldNotBeInferredFromMaintenanceLikeSourceValue() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = baseItem(now)
                .stage(IngestionStage.UPLOAD)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(0)
                .dedupeStrategy(DedupeStrategy.SKIP)
                .build();
        stubGeneratedIds(102L, 202L);
        when(mapper.pointItemToExecution("item-1", 202L, now)).thenReturn(1);

        new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.REEMBED, List.of(item), now).toBuilder()
                .initialExecutionKind(IngestionExecutionKind.INITIAL)
                .build());

        ArgumentCaptor<IngestionExecutionRecord> execution =
                ArgumentCaptor.forClass(IngestionExecutionRecord.class);
        verify(mapper).insertExecution(execution.capture());
        assertThat(execution.getValue().getExecutionKind()).isEqualTo("INITIAL");
    }

    @Test
    void save_shouldInsertItemAttemptExecutionThenAttachCurrentPointer() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = baseItem(now)
                .status(IngestionTaskItemStatus.PENDING)
                .stage(IngestionStage.PARSE)
                .targetIndexGeneration(3L)
                .dedupeStrategy(DedupeStrategy.OVERWRITE)
                .build();
        stubGeneratedIds(11L, 22L);
        when(mapper.pointItemToExecution("item-1", 22L, now)).thenReturn(1);

        new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.UPLOAD, List.of(item), now));

        InOrder order = inOrder(mapper);
        order.verify(mapper).insertTask(any());
        order.verify(mapper).insertItem(any());
        order.verify(mapper).insertParseAttempt(any());
        order.verify(mapper).insertExecution(any());
        order.verify(mapper).pointItemToExecution("item-1", 22L, now);
        ArgumentCaptor<IngestionTaskItemRecord> itemRecord =
                ArgumentCaptor.forClass(IngestionTaskItemRecord.class);
        verify(mapper).insertItem(itemRecord.capture());
        assertThat(itemRecord.getValue().getTargetIndexGeneration()).isEqualTo(3L);
    }

    @Test
    void targetGenerationAssignment_shouldOnlyAcceptPositiveValues() {
        IngestionTaskRepositoryImpl repository =
                new IngestionTaskRepositoryImpl(mapper);
        LocalDateTime now = LocalDateTime.now();

        when(mapper.assignTargetIndexGeneration(
                "item-1", "asset-1", 2L, now)).thenReturn(1);

        assertThat(repository.assignTargetIndexGeneration(
                "item-1", "asset-1", 2L, now)).isTrue();
        assertThatThrownBy(() -> repository.assignTargetIndexGeneration(
                "item-1", "asset-1", 0L, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void explicitEmbedStartMustRequireParseArtifact() {
        IngestionTask task = taskWithExplicitStage(
                IngestionExecutionStage.EMBED, null);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse artifact");
    }

    @Test
    void explicitIndexStartMustRequireParseArtifact() {
        IngestionTask task = taskWithExplicitStage(
                IngestionExecutionStage.INDEX, null);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse artifact");
    }

    @Test
    void newExecutionMustNotTurnBareObjectKeyIntoLegacyArtifact() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTask task = taskWithExplicitStage(
                IngestionExecutionStage.EMBED, "parse-result.gz");
        stubGeneratedIds(103L, 203L);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete produced artifact metadata");
        verify(mapper, never()).insertArtifact(any());
        verify(mapper, never()).pointItemToExecution(anyString(), any(), any());
    }

    @Test
    void newExecutionShouldRegisterCompleteProducedArtifactBeforePointer() {
        LocalDateTime now = LocalDateTime.now();
        String objectKey = "parse-result.json.gz";
        String digest = "a".repeat(64);
        IngestionTaskItem item = baseItem(now)
                .executionStage(IngestionExecutionStage.EMBED)
                .executionEpoch(1)
                .claimVersion(1)
                .parseResultObjectKey(objectKey)
                .parseResultArtifact(IngestionArtifactReference.builder()
                        .artifactType("PARSE_RESULT")
                        .artifactVersion(1)
                        .provenance("PRODUCED")
                        .producerClaimVersion(1L)
                        .objectKey(objectKey)
                        .contentSha256(digest)
                        .build())
                .stage(IngestionStage.EMBED)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(55)
                .build();
        stubGeneratedIds(104L, 204L);
        IngestionArtifactRecord stored = new IngestionArtifactRecord();
        stored.setExecutionId(204L);
        stored.setArtifactType("PARSE_RESULT");
        stored.setArtifactVersion(1);
        stored.setProvenance("PRODUCED");
        stored.setProducerClaimVersion(1L);
        stored.setObjectKey(objectKey);
        stored.setContentSha256(digest);
        stored.setCreatedAt(now);
        when(mapper.findArtifact(204L, "PARSE_RESULT"))
                .thenReturn(Optional.of(stored));
        when(mapper.pointItemToExecution("item-1", 204L, now)).thenReturn(1);

        new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.REEMBED, List.of(item), now));

        ArgumentCaptor<IngestionArtifactRecord> artifact =
                ArgumentCaptor.forClass(IngestionArtifactRecord.class);
        verify(mapper).insertArtifact(artifact.capture());
        assertThat(artifact.getValue().getExecutionId()).isEqualTo(204L);
        assertThat(artifact.getValue().getArtifactType()).isEqualTo("PARSE_RESULT");
        assertThat(artifact.getValue().getArtifactVersion()).isEqualTo(1);
        assertThat(artifact.getValue().getProvenance()).isEqualTo("PRODUCED");
        assertThat(artifact.getValue().getProducerClaimVersion()).isEqualTo(1L);
        assertThat(artifact.getValue().getObjectKey()).isEqualTo(objectKey);
        assertThat(artifact.getValue().getContentSha256()).isEqualTo(digest);
        verify(mapper).pointItemToExecution("item-1", 204L, now);
    }

    @Test
    void uniformItemDedupe_shouldBeNormalizedToTaskRecord() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem first = baseItem(now)
                .id("item-1")
                .status(IngestionTaskItemStatus.SKIPPED)
                .stage(IngestionStage.ASKABLE)
                .dedupeStrategy(DedupeStrategy.VERSIONED)
                .finishedAt(now)
                .build();
        IngestionTaskItem second = baseItem(now)
                .id("item-2")
                .status(IngestionTaskItemStatus.SKIPPED)
                .stage(IngestionStage.ASKABLE)
                .dedupeStrategy(DedupeStrategy.VERSIONED)
                .finishedAt(now)
                .build();

        new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.UPLOAD, List.of(first, second), now));

        ArgumentCaptor<IngestionTaskRecord> taskRecord =
                ArgumentCaptor.forClass(IngestionTaskRecord.class);
        verify(mapper).insertTask(taskRecord.capture());
        assertThat(taskRecord.getValue().getDedupeStrategy()).isEqualTo("VERSIONED");
        verify(mapper, never()).insertExecution(any());
    }

    @Test
    void mixedItemDedupe_shouldBeRejectedBeforeAnyWrite() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem first = baseItem(now)
                .id("item-1")
                .status(IngestionTaskItemStatus.SKIPPED)
                .stage(IngestionStage.ASKABLE)
                .dedupeStrategy(DedupeStrategy.SKIP)
                .finishedAt(now)
                .build();
        IngestionTaskItem second = baseItem(now)
                .id("item-2")
                .status(IngestionTaskItemStatus.SKIPPED)
                .stage(IngestionStage.ASKABLE)
                .dedupeStrategy(DedupeStrategy.OVERWRITE)
                .finishedAt(now)
                .build();

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.UPLOAD, List.of(first, second), now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same dedupe strategy");
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void retry_shouldInsertNewAttemptAndExecutionBeforeCasPointer() {
        LocalDateTime now = LocalDateTime.now();
        FailedItemRetryRecord failed = new FailedItemRetryRecord();
        failed.setItemId("item-1");
        failed.setTaskId("task-1");
        failed.setKbId("kb-1");
        failed.setCurrentExecutionId(41L);
        failed.setExecutionEpoch(3L);
        failed.setExecutionStatus("FAILED");
        failed.setParseAttemptNo(7);
        failed.setSourceRevision("v1:source");
        when(mapper.selectFailedItemForRetryForUpdate(
                "kb-1", "task-1", "item-1", 7)).thenReturn(Optional.of(failed));
        stubGeneratedIds(51L, 61L);
        when(mapper.resetFailedItemPointer(
                "kb-1", "task-1", "item-1", 41L, 61L,
                IngestionPublicProjectionPolicy.explicitRetry(), now)).thenReturn(1);

        boolean reset = new IngestionTaskRepositoryImpl(mapper).resetFailedItem(
                "kb-1", "task-1", "item-1", 7, 8, "task-1:item-1:8", now);

        assertThat(reset).isTrue();
        ArgumentCaptor<IngestionParseAttemptRecord> attempt =
                ArgumentCaptor.forClass(IngestionParseAttemptRecord.class);
        ArgumentCaptor<IngestionExecutionRecord> execution =
                ArgumentCaptor.forClass(IngestionExecutionRecord.class);
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectFailedItemForRetryForUpdate(
                "kb-1", "task-1", "item-1", 7);
        order.verify(mapper).insertParseAttempt(attempt.capture());
        order.verify(mapper).insertExecution(execution.capture());
        order.verify(mapper).resetFailedItemPointer(
                "kb-1", "task-1", "item-1", 41L, 61L,
                IngestionPublicProjectionPolicy.explicitRetry(), now);

        assertThat(attempt.getValue().getId()).isEqualTo(51L);
        assertThat(attempt.getValue().getAttemptNo()).isEqualTo(8);
        assertThat(attempt.getValue().getRequestId()).isEqualTo("task-1:item-1:8");
        assertThat(attempt.getValue().getSourceRevision()).isEqualTo("v1:source");
        assertThat(execution.getValue().getId()).isEqualTo(61L);
        assertThat(execution.getValue().getParseAttemptId()).isEqualTo(51L);
        assertThat(execution.getValue().getExecutionEpoch()).isEqualTo(4L);
        assertThat(execution.getValue().getExecutionKind()).isEqualTo("EXPLICIT_RETRY");
        assertThat(execution.getValue().getPhase()).isEqualTo("PARSE_SUBMIT");
    }

    @Test
    void retry_shouldRejectPreflightFailureWithoutCreatingExecutionHistory() {
        LocalDateTime now = LocalDateTime.now();
        FailedItemRetryRecord failed = new FailedItemRetryRecord();
        failed.setItemId("item-1");
        failed.setTaskId("task-1");
        failed.setKbId("kb-1");
        failed.setCurrentExecutionId(null);
        failed.setExecutionEpoch(null);
        failed.setExecutionStatus(null);
        failed.setParseAttemptNo(1);
        when(mapper.selectFailedItemForRetryForUpdate(
                "kb-1", "task-1", "item-1", 1)).thenReturn(Optional.of(failed));

        boolean reset = new IngestionTaskRepositoryImpl(mapper).resetFailedItem(
                "kb-1", "task-1", "item-1",
                1, 2, "task-1:item-1:2", now);

        assertThat(reset).isFalse();
        verify(mapper, never()).insertParseAttempt(any());
        verify(mapper, never()).insertExecution(any());
        verify(mapper, never()).resetFailedItemPointer(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retry_shouldThrowSoPreparedRowsRollbackWhenPointerCasFails() {
        LocalDateTime now = LocalDateTime.now();
        FailedItemRetryRecord failed = new FailedItemRetryRecord();
        failed.setItemId("item-1");
        failed.setTaskId("task-1");
        failed.setKbId("kb-1");
        failed.setCurrentExecutionId(41L);
        failed.setExecutionEpoch(3L);
        failed.setExecutionStatus("FAILED");
        failed.setParseAttemptNo(7);
        when(mapper.selectFailedItemForRetryForUpdate(
                "kb-1", "task-1", "item-1", 7)).thenReturn(Optional.of(failed));
        stubGeneratedIds(51L, 61L);
        when(mapper.resetFailedItemPointer(
                "kb-1", "task-1", "item-1", 41L, 61L,
                IngestionPublicProjectionPolicy.explicitRetry(), now)).thenReturn(0);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper)
                .resetFailedItem(
                        "kb-1", "task-1", "item-1",
                        7, 8, "task-1:item-1:8", now))
                .isInstanceOf(IngestionRetryConflictException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void claim_shouldUsePolicyAndAssembleRegisteredArtifactMetadata() {
        LocalDateTime now = LocalDateTime.now();
        ClaimCandidateRecord candidate = new ClaimCandidateRecord();
        candidate.setItemId("item-1");
        candidate.setItemProgress(60);
        candidate.setExecutionId(61L);
        candidate.setExecutionEpoch(1L);
        candidate.setPhase("PARSE_SUBMIT");
        candidate.setClaimVersion(0L);
        when(mapper.selectClaimableItemForUpdate("item-1"))
                .thenReturn(Optional.of(candidate));
        when(mapper.claimExecution(any(), anyString(), org.mockito.ArgumentMatchers.eq(60L)))
                .thenReturn(1);
        when(mapper.projectClaimedItem(
                "item-1", 61L,
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.PARSE_SUBMIT, 60)))
                .thenReturn(1);

        ClaimedExecutionRecord claimed = new ClaimedExecutionRecord();
        claimed.setItemId("item-1");
        claimed.setTaskId("task-1");
        claimed.setKbId("kb-1");
        claimed.setTaskCreatedBy("user-1");
        claimed.setAssetId("asset-1");
        claimed.setItemProgress(60);
        claimed.setClaimUpdatedAt(now);
        claimed.setExecutionEpoch(1L);
        claimed.setPhase("PARSE_SUBMIT");
        claimed.setClaimVersion(1L);
        claimed.setLeaseToken("lease-1");
        claimed.setLeaseUntil(now.plusMinutes(1));
        claimed.setParseAttemptNo(1);
        claimed.setRequestId("task-1:item-1:1");
        claimed.setSourceRevision("v1:source");
        claimed.setParseResultObjectKey("parse-result.gz");
        claimed.setParseResultArtifactVersion(1);
        claimed.setParseResultArtifactProvenance("PRODUCED");
        claimed.setParseResultProducerClaimVersion(1L);
        claimed.setParseResultSha256("a".repeat(64));
        when(mapper.findClaimedExecution(
                org.mockito.ArgumentMatchers.eq("item-1"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(Optional.of(claimed));

        IngestionTaskItem result =
                new IngestionTaskRepositoryImpl(mapper).claimOne("item-1", 60)
                        .orElseThrow();

        assertThat(result.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(result.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(result.getProgress()).isEqualTo(60);
        assertThat(result.getClaimVersion()).isEqualTo(1L);
        assertThat(result.getParseResultArtifact().getProvenance())
                .isEqualTo("PRODUCED");
        assertThat(result.getParseResultArtifact().getContentSha256())
                .isEqualTo("a".repeat(64));
        verify(mapper).projectClaimedItem(
                "item-1", 61L,
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.PARSE_SUBMIT, 60));
    }

    @Test
    void transition_shouldRejectPublicProjectionThatDoesNotMatchNextPhase() {
        LocalDateTime now = LocalDateTime.now();
        IngestionClaimTransition inconsistent = IngestionClaimTransition.builder()
                .itemId("item-1")
                .executionEpoch(1L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_PERSIST)
                .expectedClaimVersion(1L)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.EMBED)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(now)
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(55)
                .parseAttempt(1)
                .build();

        assertThatThrownBy(() ->
                new IngestionTaskRepositoryImpl(mapper).transitionClaim(inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public projection");
        verify(mapper, never()).transitionExecution(any());
    }

    @Test
    void retainLeaseMustOnlyBeUsedForEmbedToIndexHandoff() {
        LocalDateTime now = LocalDateTime.now();
        IngestionClaimTransition invalid = IngestionClaimTransition.builder()
                .itemId("item-1")
                .executionEpoch(1L)
                .expectedExecutionStage(IngestionExecutionStage.INDEX)
                .expectedClaimVersion(1L)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.INDEX)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(now)
                .retainLease(true)
                .stage(IngestionStage.INDEX)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(75)
                .parseAttempt(1)
                .build();

        assertThatThrownBy(() ->
                new IngestionTaskRepositoryImpl(mapper).transitionClaim(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMBED directly to INDEX");
        verify(mapper, never()).transitionExecution(any());
    }

    @Test
    void save_shouldRejectItemWithDifferentParentBeforeWriting() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = baseItem(now)
                .taskId("other-task")
                .build();

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task(
                IngestionSourceType.UPLOAD, List.of(item), now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent task");
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void save_shouldRejectEitherHalfOfLeasePairBeforeWriting() {
        LocalDateTime now = LocalDateTime.now();
        List<IngestionTaskItem> invalidItems = List.of(
                baseItem(now)
                        .leaseToken("lease-without-expiry")
                        .build(),
                baseItem(now)
                        .leaseUntil(now.plusMinutes(1))
                        .build());

        for (IngestionTaskItem item : invalidItems) {
            assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task(
                    IngestionSourceType.UPLOAD, List.of(item), now)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both present or both absent");
        }
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void save_shouldRejectInvalidExecutionCountersBeforeWriting() {
        LocalDateTime now = LocalDateTime.now();
        List<IngestionTaskItem> invalidItems = List.of(
                baseItem(now).parseAttempt(0).build(),
                baseItem(now).executionEpoch(0L).build(),
                baseItem(now).claimVersion(-1L).build(),
                baseItem(now).stageRetryCount(-1).build());

        for (IngestionTaskItem item : invalidItems) {
            assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task(
                    IngestionSourceType.UPLOAD, List.of(item), now)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void save_shouldRejectInvalidTaskCountsBeforeWriting() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTask valid = task(
                IngestionSourceType.UPLOAD, List.of(baseItem(now).build()), now);
        List<IngestionTask> invalidTasks = List.of(
                valid.toBuilder().totalCount(-1).build(),
                valid.toBuilder().successCount(-1).build(),
                valid.toBuilder().failureCount(-1).build(),
                valid.toBuilder().runningCount(-1).build(),
                valid.toBuilder().totalCount(1).successCount(2).build(),
                valid.toBuilder().totalCount(2).build());

        for (IngestionTask invalid : invalidTasks) {
            assertThatThrownBy(() ->
                    new IngestionTaskRepositoryImpl(mapper).save(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void transition_shouldRejectTerminalStateWithoutFinishedAt() {
        LocalDateTime now = LocalDateTime.now();
        IngestionClaimTransition invalid = validTransition(now)
                .expectedExecutionStage(IngestionExecutionStage.INDEX)
                .nextExecutionStage(IngestionExecutionStage.COMPLETE)
                .nextActionAt(null)
                .stage(IngestionStage.ASKABLE)
                .status(IngestionTaskItemStatus.SUCCESS)
                .progress(100)
                .finishedAt(null)
                .build();

        assertThatThrownBy(() ->
                new IngestionTaskRepositoryImpl(mapper).transitionClaim(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedAt");
        verify(mapper, never()).transitionExecution(any());
    }

    @Test
    void transition_shouldRejectNegativeRetryCount() {
        LocalDateTime now = LocalDateTime.now();
        IngestionClaimTransition invalid = validTransition(now)
                .nextStageRetryCount(-1)
                .build();

        assertThatThrownBy(() ->
                new IngestionTaskRepositoryImpl(mapper).transitionClaim(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextStageRetryCount");
        verify(mapper, never()).transitionExecution(any());
    }

    @Test
    void updateClaimContext_shouldRejectNonParsePhaseBeforeWriting() {
        IngestionClaimContext invalid = IngestionClaimContext.builder()
                .itemId("item-1")
                .executionEpoch(1L)
                .expectedExecutionStage(IngestionExecutionStage.EMBED)
                .claimVersion(1L)
                .leaseToken("lease-1")
                .parseAttempt(1)
                .doclingRequestId("task-1:item-1:1")
                .sourceRevision("v1:source")
                .parseRequestSnapshot("{}")
                .build();

        assertThatThrownBy(() ->
                new IngestionTaskRepositoryImpl(mapper).updateClaimContext(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse phase");
        verify(mapper, never()).updateClaimContext(any());
    }

    @Test
    void retry_shouldRejectBrokenCurrentExecutionPointerBeforePreparingRows() {
        LocalDateTime now = LocalDateTime.now();
        FailedItemRetryRecord failed = new FailedItemRetryRecord();
        failed.setItemId("item-1");
        failed.setTaskId("task-1");
        failed.setKbId("kb-1");
        failed.setCurrentExecutionId(41L);
        failed.setExecutionEpoch(1L);
        failed.setExecutionStatus(null);
        failed.setParseAttemptNo(1);
        when(mapper.selectFailedItemForRetryForUpdate(
                "kb-1", "task-1", "item-1", 1)).thenReturn(Optional.of(failed));

        boolean reset = new IngestionTaskRepositoryImpl(mapper).resetFailedItem(
                "kb-1", "task-1", "item-1", 1, 2, "task-1:item-1:2", now);

        assertThat(reset).isFalse();
        verify(mapper, never()).insertParseAttempt(any());
        verify(mapper, never()).insertExecution(any());
    }

    private IngestionClaimTransition.IngestionClaimTransitionBuilder validTransition(
            LocalDateTime now) {
        return IngestionClaimTransition.builder()
                .itemId("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .executionEpoch(1L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_WAIT)
                .expectedClaimVersion(1L)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.PARSE_PERSIST)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(now)
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(20)
                .parseAttempt(1)
                .updatedBy("user-1")
                .updatedAt(now);
    }

    private void stubGeneratedIds(long parseAttemptId, long executionId) {
        doAnswer(invocation -> {
            IngestionParseAttemptRecord record = invocation.getArgument(0);
            record.setId(parseAttemptId);
            return 1;
        }).when(mapper).insertParseAttempt(any());
        doAnswer(invocation -> {
            IngestionExecutionRecord record = invocation.getArgument(0);
            record.setId(executionId);
            return 1;
        }).when(mapper).insertExecution(any());
    }

    private IngestionTask taskWithExplicitStage(IngestionExecutionStage executionStage,
                                                String parseArtifact) {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = baseItem(now)
                .executionStage(executionStage)
                .parseResultObjectKey(parseArtifact)
                .stage(executionStage == IngestionExecutionStage.INDEX
                        ? IngestionStage.INDEX : IngestionStage.EMBED)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(0)
                .build();
        return task(IngestionSourceType.REEMBED, List.of(item), now);
    }

    private IngestionTaskItem.IngestionTaskItemBuilder baseItem(LocalDateTime now) {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .fileName("document.pdf")
                .parseAttempt(1)
                .executionEpoch(1L)
                .claimVersion(0L)
                .stageRetryCount(0)
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(0)
                .createdAt(now)
                .updatedAt(now);
    }

    private IngestionTask task(IngestionSourceType sourceType,
                               List<IngestionTaskItem> items,
                               LocalDateTime now) {
        return IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .sourceType(sourceType)
                .initialExecutionKind(switch (sourceType) {
                    case REPARSE -> IngestionExecutionKind.REPARSE;
                    case REEMBED -> IngestionExecutionKind.REEMBED;
                    case UPLOAD, URL, RETRY -> IngestionExecutionKind.INITIAL;
                })
                .status(IngestionTaskStatus.PENDING)
                .totalCount(items.size())
                .createdBy("user-a")
                .updatedBy("user-a")
                .createdAt(now)
                .updatedAt(now)
                .items(items)
                .build();
    }
}
