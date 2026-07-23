package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionExecutionKind;
import com.anchr.core.ingestion.domain.model.IngestionRetryConflictException;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionApplicationServiceImplTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private IdGen idGen;
    @Mock
    private ActivityEventService activityEventService;
    @Mock
    private IngestionTaskProcessor ingestionTaskProcessor;
    @Mock
    private IngestionCreateTransactionRunner transactionRunner;

    private IngestionApplicationServiceImpl service;
    private final AtomicReference<IngestionTask> savedTask = new AtomicReference<>();
    private long nextId;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        service = new IngestionApplicationServiceImpl(
                knowledgeBaseService,
                assetRepository,
                knowledgeBaseRepository,
                ingestionTaskRepository,
                new IngestionCapabilityService(),
                idGen,
                activityEventService,
                ingestionTaskProcessor,
                transactionRunner
        );
        lenient().when(transactionRunner.write(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(transactionRunner.read(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        nextId = 1000L;
        lenient().when(idGen.nextIdStr()).thenAnswer(invocation -> String.valueOf(nextId++));
        lenient().when(ingestionTaskRepository.findById(eq("kb-1"), any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        lenient().doAnswer(invocation -> {
            savedTask.set(invocation.getArgument(0));
            return null;
        }).when(ingestionTaskRepository).save(any());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void createTask_shouldDefaultToSkipWhenStrategyMissing() {
        Asset existing = existingAsset("asset-old", "hash-a");
        when(assetRepository.findActiveByHash("kb-1", "hash-a")).thenReturn(Optional.of(existing));

        IngestionTask task = service.createTask("kb-1", command(null, IngestionSourceType.UPLOAD, "hash-a")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.SKIPPED);
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.SKIPPED);
        assertThat(item.getDuplicateAssetId()).isEqualTo("asset-old");
    }

    @Test
    void createTask_shouldSkipDuplicateWhenStrategyIsSkip() {
        Asset existing = existingAsset("asset-old", "hash-a");
        when(assetRepository.findActiveByHash("kb-1", "hash-a")).thenReturn(Optional.of(existing));

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-a")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(task.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.SKIPPED);
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.SKIPPED);
        assertThat(item.getAssetId()).isEqualTo("asset-old");
        assertThat(item.getDuplicateAssetId()).isEqualTo("asset-old");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createTask_shouldCreateNewAssetWhenSkipDoesNotMatch() {
        when(assetRepository.findActiveByHash("kb-1", "hash-new")).thenReturn(Optional.empty());

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-new")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.NEW);
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        assertThat(item.getDuplicateAssetId()).isNull();
        assertThat(item.getParseAttempt()).isEqualTo(1);
        assertThat(item.getDoclingRequestId()).matches("[0-9]+:[0-9]+:1");
        assertThat(item.getSourceRevision()).startsWith("v1:").hasSize(67);
        assertThat(item.getDoclingJobId()).isNull();
        verify(assetRepository).save(any());
    }

    @Test
    void createTask_shouldMarkOverwriteOnlyWhenDuplicateExists() {
        Asset existing = existingAsset("asset-old", "hash-a");
        when(assetRepository.findActiveByHash("kb-1", "hash-a")).thenReturn(Optional.of(existing));

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.OVERWRITE, IngestionSourceType.UPLOAD, "hash-a")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.OVERWRITE);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.OVERWRITTEN);
        assertThat(item.getDuplicateAssetId()).isEqualTo("asset-old");
        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getVersionNo()).isEqualTo(1);
    }

    @Test
    void createTask_shouldReturnNewWhenOverwriteDoesNotMatch() {
        when(assetRepository.findActiveByHash("kb-1", "hash-new")).thenReturn(Optional.empty());

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.OVERWRITE, IngestionSourceType.UPLOAD, "hash-new")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.OVERWRITE);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.NEW);
        assertThat(item.getDuplicateAssetId()).isNull();
    }

    @Test
    void createTask_shouldCreateVersionedAssetWhenDuplicateExists() {
        Asset existing = existingAsset("asset-old", "hash-a").toBuilder()
                .versionGroupId("group-1")
                .versionNo(2)
                .build();
        when(assetRepository.findActiveByHash("kb-1", "hash-a")).thenReturn(Optional.of(existing));
        when(assetRepository.findMaxVersionNo("kb-1", "group-1")).thenReturn(2);

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.VERSIONED, IngestionSourceType.UPLOAD, "hash-a")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.VERSIONED);
        assertThat(item.getDuplicateAssetId()).isEqualTo("asset-old");
        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getVersionGroupId()).isEqualTo("group-1");
        assertThat(assetCaptor.getValue().getVersionNo()).isEqualTo(3);
        assertThat(assetCaptor.getValue().getPreviousAssetId()).isEqualTo("asset-old");
    }

    @Test
    void createTask_shouldApplyDedupeToUrlImportWhenFileHashExists() {
        Asset existing = existingAsset("asset-url", "hash-url");
        when(assetRepository.findActiveByHash("kb-1", "hash-url")).thenReturn(Optional.of(existing));

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.URL, "hash-url")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.SKIPPED);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.SKIPPED);
        assertThat(item.getAssetId()).isEqualTo("asset-url");
    }

    @Test
    void createTask_shouldUseUrlPendingProjectionForNewUrlAsset() {
        when(assetRepository.findActiveByHash("kb-1", "hash-url"))
                .thenReturn(Optional.empty());

        IngestionTask task = service.createTask(
                "kb-1",
                command(DedupeStrategy.SKIP, IngestionSourceType.URL, "hash-url")).task();

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(item.getProgress()).isEqualTo(10);
    }

    @Test
    void genericCreateTask_shouldKeepLegacyUploadProjectionForMaintenanceSourceValues() {
        for (IngestionSourceType sourceType : List.of(
                IngestionSourceType.REPARSE,
                IngestionSourceType.REEMBED,
                IngestionSourceType.RETRY)) {
            IngestionTask task = service.createTask(
                    "kb-1",
                    command(DedupeStrategy.SKIP, sourceType, "hash-" + sourceType)).task();

            IngestionTaskItem item = task.getItems().getFirst();
            assertThat(item.getStage()).isEqualTo(IngestionStage.UPLOAD);
            assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.PENDING);
            assertThat(item.getProgress()).isZero();
            assertThat(savedTask.get().getInitialExecutionKind())
                    .isEqualTo(IngestionExecutionKind.INITIAL);
        }
    }

    @Test
    void createMaintenanceTasks_shouldUseSourceSpecificPendingProjections() {
        Asset document = existingAsset("asset-1", "hash-a").toBuilder()
                .parseStatus(DocumentParseStatus.SUCCESS)
                .build();
        when(knowledgeBaseService.getDocument("kb-1", "asset-1"))
                .thenReturn(document);

        IngestionTask reparse = service.createReparseTask("kb-1", "asset-1");
        assertThat(reparse.getItems().getFirst().getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(reparse.getItems().getFirst().getStatus())
                .isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(reparse.getItems().getFirst().getProgress()).isEqualTo(20);
        assertThat(savedTask.get().getInitialExecutionKind())
                .isEqualTo(IngestionExecutionKind.REPARSE);

        IngestionTask reembed = service.createReembedTask("kb-1", "asset-1");
        assertThat(reembed.getItems().getFirst().getStage()).isEqualTo(IngestionStage.EMBED);
        assertThat(reembed.getItems().getFirst().getStatus())
                .isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(reembed.getItems().getFirst().getProgress()).isEqualTo(60);
        assertThat(savedTask.get().getInitialExecutionKind())
                .isEqualTo(IngestionExecutionKind.REEMBED);
    }

    @Test
    void createTask_withoutClientRequestId_shouldPreserveLegacyCreateEveryTimeBehavior() {
        IngestionApplicationService.IngestionCreateCommand command =
                command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-new");

        var first = service.createTask("kb-1", command);
        var second = service.createTask("kb-1", command);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(first.task().getId()).isNotEqualTo(second.task().getId());
        assertThat(first.task().getClientRequestId()).isNull();
        assertThat(first.task().getRequestHash()).isNull();
        verify(ingestionTaskRepository, times(2)).save(any());
        verify(ingestionTaskProcessor, times(2)).submit(eq("kb-1"), any(), eq("user-a"));
        verify(ingestionTaskRepository, never()).findByClientRequestId(any(), any());
    }

    @Test
    void createTask_sameNormalizedRequest_shouldReplayWithoutRepeatingWrites() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-1"))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        var firstCommand = new IngestionApplicationService.IngestionCreateCommand(
                " request-1 ",
                null,
                null,
                List.of(item(" mysql.pdf ", " MySQL ", "pdf", " application/pdf ",
                        " objects/mysql.pdf ", " hash-a ", null)));
        var replayCommand = new IngestionApplicationService.IngestionCreateCommand(
                "request-1",
                IngestionSourceType.UPLOAD,
                DedupeStrategy.SKIP,
                List.of(item("mysql.pdf", "MySQL", "PDF", "application/pdf",
                        "objects/mysql.pdf", "hash-a", null)));

        var first = service.createTask(" kb-1 ", firstCommand);
        var replay = service.createTask("kb-1", replayCommand);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.task().getId()).isEqualTo(first.task().getId());
        assertThat(first.task().getClientRequestId()).isEqualTo("request-1");
        assertThat(first.task().getRequestHash())
                .startsWith("v1:")
                .hasSize(67);
        verify(ingestionTaskRepository).save(any());
        verify(assetRepository).save(any());
        verify(activityEventService).recordDocumentImported(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(knowledgeBaseRepository).refreshDocumentStats("kb-1", "user-a", false);
        verify(ingestionTaskProcessor).submit("kb-1", first.task().getId(), "user-a");
    }

    @Test
    void createTask_sameIdWithDifferentPayload_shouldRejectWithoutSecondWrite() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-1"))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        var first = idempotentCommand("request-1", "MySQL");
        var changedTitle = idempotentCommand("request-1", "PostgreSQL");

        service.createTask("kb-1", first);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-1", changedTitle));

        assertThat(error.getError()).isEqualTo(ApiError.IDEMPOTENCY_KEY_REUSED);
        verify(ingestionTaskRepository).save(any());
        verify(assetRepository).save(any());
        verify(ingestionTaskProcessor).submit(eq("kb-1"), any(), eq("user-a"));
    }

    @Test
    void createTask_existingReplay_shouldNotRequireKbToRemainActive() {
        IngestionApplicationService.IngestionCreateCommand command =
                idempotentCommand("request-archived", "MySQL");
        String requestHash = IngestionRequestHasher.hash(
                "kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, command.items());
        IngestionTask winner = task("task-winner", "kb-1", "request-archived", requestHash);
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-archived"))
                .thenReturn(Optional.of(winner));

        var replay = service.createTask("kb-1", command);

        assertThat(replay.created()).isFalse();
        assertThat(replay.task()).isSameAs(winner);
        verify(knowledgeBaseService, never()).get(any());
        verify(transactionRunner, never()).write(any());
    }

    @Test
    void createTask_existingConflict_shouldNotBecomeCleanupAuthorizedKbNotFound() {
        IngestionApplicationService.IngestionCreateCommand accepted =
                idempotentCommand("request-archived", "MySQL");
        String requestHash = IngestionRequestHasher.hash(
                "kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, accepted.items());
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-archived"))
                .thenReturn(Optional.of(task("task-winner", "kb-1", "request-archived", requestHash)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-1", idempotentCommand("request-archived", "Changed")));

        assertThat(error.getError()).isEqualTo(ApiError.IDEMPOTENCY_KEY_REUSED);
        verify(knowledgeBaseService, never()).get(any());
        verify(transactionRunner, never()).write(any());
    }

    @Test
    void createTask_newId_shouldStillRequireAnActiveKbBeforeWriting() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-new"))
                .thenReturn(Optional.empty());
        doThrow(new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND))
                .when(knowledgeBaseService).get("kb-1");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-1", idempotentCommand("request-new", "MySQL")));

        assertThat(error.getError()).isEqualTo(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        verify(transactionRunner, never()).write(any());
        verify(ingestionTaskRepository, never()).save(any());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createTask_legacyRequest_shouldStillRequireAnActiveKbBeforeWriting() {
        doThrow(new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND))
                .when(knowledgeBaseService).get("kb-1");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-1",
                        command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-a")));

        assertThat(error.getError()).isEqualTo(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        verify(ingestionTaskRepository, never()).findByClientRequestId(any(), any());
        verify(transactionRunner, never()).write(any());
    }

    @Test
    void createTask_sameIdWithDifferentKb_shouldRejectWithoutWriting() {
        IngestionApplicationService.IngestionCreateCommand command = idempotentCommand("request-1", "MySQL");
        String requestHash = IngestionRequestHasher.hash(
                "kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, command.items());
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-1"))
                .thenReturn(Optional.of(task("task-winner", "kb-1", "request-1", requestHash)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-2", command));

        assertThat(error.getError()).isEqualTo(ApiError.IDEMPOTENCY_KEY_REUSED);
        verify(ingestionTaskRepository, never()).save(any());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createTask_sameIdWithReorderedItems_shouldReject() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-order"))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        var firstItem = item("a.pdf", "A", "PDF", "application/pdf", "objects/a", "hash-a", null);
        var secondItem = item("b.pdf", "B", "PDF", "application/pdf", "objects/b", "hash-b", null);
        var first = new IngestionApplicationService.IngestionCreateCommand(
                "request-order", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                List.of(firstItem, secondItem));
        var reordered = new IngestionApplicationService.IngestionCreateCommand(
                "request-order", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                List.of(secondItem, firstItem));

        service.createTask("kb-1", first);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask("kb-1", reordered));

        assertThat(error.getError()).isEqualTo(ApiError.IDEMPOTENCY_KEY_REUSED);
        verify(ingestionTaskRepository).save(any());
    }

    @Test
    void createTask_clientRequestUniqueRace_shouldReadAndReturnCommittedWinner() {
        IngestionApplicationService.IngestionCreateCommand command = idempotentCommand("request-race", "MySQL");
        String requestHash = IngestionRequestHasher.hash(
                "kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, command.items());
        IngestionTask winner = task("task-winner", "kb-1", "request-race", requestHash);
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateKeyException(
                "Duplicate entry for key 'uk_ingestion_task_creator_request'"))
                .when(ingestionTaskRepository).save(any());

        var result = service.createTask("kb-1", command);

        assertThat(result.created()).isFalse();
        assertThat(result.task()).isSameAs(winner);
        verify(transactionRunner).read(any());
        verify(activityEventService, never()).recordDocumentImported(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    @Test
    void createTask_nonIdempotencyDuplicate_shouldNotBeMaskedOrReadWinner() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-pk"))
                .thenReturn(Optional.empty());
        DuplicateKeyException duplicate = new DuplicateKeyException("Duplicate entry for PRIMARY");
        doThrow(duplicate).when(ingestionTaskRepository).save(any());

        DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
                () -> service.createTask("kb-1", idempotentCommand("request-pk", "MySQL")));

        assertThat(thrown).isSameAs(duplicate);
        verify(transactionRunner, never()).read(any());
    }

    @Test
    void getTaskByClientRequestId_shouldScopeLookupToCurrentUserAndKb() {
        IngestionTask winner = task("task-winner", "kb-1", "request-lookup", "v1:hash");
        when(ingestionTaskRepository.findByClientRequestId("user-a", "request-lookup"))
                .thenReturn(Optional.of(winner));

        assertThat(service.getTaskByClientRequestId("kb-1", "request-lookup")).isSameAs(winner);

        BusinessException wrongKb = assertThrows(BusinessException.class,
                () -> service.getTaskByClientRequestId("kb-2", "request-lookup"));
        assertThat(wrongKb.getError()).isEqualTo(ApiError.INGESTION_TASK_NOT_FOUND);
        verify(knowledgeBaseService, never()).get(any());
    }

    @Test
    void getTaskByClientRequestId_missingOrUnsafeId_shouldFailWithoutWriting() {
        when(ingestionTaskRepository.findByClientRequestId("user-a", "missing"))
                .thenReturn(Optional.empty());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.getTaskByClientRequestId("kb-1", "missing"));
        BusinessException unsafe = assertThrows(BusinessException.class,
                () -> service.getTaskByClientRequestId("kb-1", "folder/request"));

        assertThat(missing.getError()).isEqualTo(ApiError.INGESTION_TASK_NOT_FOUND);
        assertThat(unsafe.getError()).isEqualTo(ApiError.INVALID_REQUEST);
        verify(knowledgeBaseService, never()).get(any());
        verify(ingestionTaskRepository, never()).save(any());
    }

    @Test
    void retryItem_shouldAdvanceParseIdentityWithExplicitCasValues() {
        IngestionTaskItem failedItem = failedItem("item-1", 3, "task-1:item-1:3");
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.findRetryItem("kb-1", "task-1", "item-1"))
                .thenReturn(Optional.of(failedItem));
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"),
                eq(3), eq(4), eq("task-1:item-1:4"), any(LocalDateTime.class)))
                .thenReturn(true);

        service.retryItem("kb-1", "task-1", "item-1");

        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"),
                eq(3), eq(4), eq("task-1:item-1:4"), any(LocalDateTime.class));
        verify(ingestionTaskRepository)
                .refreshSummary(eq("kb-1"), eq("task-1"), eq("user-a"), any(LocalDateTime.class));
        verify(ingestionTaskProcessor).submit("kb-1", "task-1", "user-a");
    }

    @Test
    void retryFailed_shouldAdvanceEveryIdentityIncludingLegacyNullRequestId() {
        IngestionTaskItem legacyItem = failedItem("item-legacy", 1, null);
        IngestionTaskItem currentItem = failedItem("item-current", 4, "task-1:item-current:4");
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(legacyItem, currentItem))
                .build());
        when(ingestionTaskRepository.listFailedItems("kb-1", "task-1"))
                .thenReturn(List.of(legacyItem, currentItem));
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-legacy"),
                eq(1), eq(2), eq("task-1:item-legacy:2"), any(LocalDateTime.class)))
                .thenReturn(true);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-current"),
                eq(4), eq(5), eq("task-1:item-current:5"), any(LocalDateTime.class)))
                .thenReturn(true);

        service.retryFailed("kb-1", "task-1");

        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-legacy"),
                eq(1), eq(2), eq("task-1:item-legacy:2"), any(LocalDateTime.class));
        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-current"),
                eq(4), eq(5), eq("task-1:item-current:5"), any(LocalDateTime.class));
        verify(ingestionTaskRepository)
                .refreshSummary(eq("kb-1"), eq("task-1"), eq("user-a"), any(LocalDateTime.class));
        verify(ingestionTaskProcessor).submit("kb-1", "task-1", "user-a");
    }

    @Test
    void retryItem_whenCasLosesRace_shouldRejectWithoutSchedulingProcessor() {
        IngestionTaskItem failedItem = failedItem("item-1", 2, "task-1:item-1:2");
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.findRetryItem("kb-1", "task-1", "item-1"))
                .thenReturn(Optional.of(failedItem));
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"),
                eq(2), eq(3), eq("task-1:item-1:3"), any(LocalDateTime.class)))
                .thenThrow(new IngestionRetryConflictException("pointer changed"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retryItem("kb-1", "task-1", "item-1"));

        assertThat(error.getError()).isEqualTo(ApiError.INGEST_RETRY_ONLY_FAILED);
        verify(ingestionTaskRepository, never())
                .refreshSummary(any(), any(), any(), any(LocalDateTime.class));
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    private IngestionApplicationService.IngestionCreateCommand command(DedupeStrategy strategy,
                                                                       IngestionSourceType sourceType,
                                                                       String fileHash) {
        return new IngestionApplicationService.IngestionCreateCommand(
                null,
                sourceType,
                strategy,
                List.of(new IngestionApplicationService.IngestionCreateItemCommand(
                        "mysql.pdf",
                        "MySQL",
                        "PDF",
                        "application/pdf",
                        1024L,
                        "objects/mysql.pdf",
                        fileHash,
                        sourceType == IngestionSourceType.URL ? "https://example.com/mysql.pdf" : null
                ))
        );
    }

    private IngestionApplicationService.IngestionCreateCommand idempotentCommand(String clientRequestId,
                                                                                  String title) {
        return new IngestionApplicationService.IngestionCreateCommand(
                clientRequestId,
                IngestionSourceType.UPLOAD,
                DedupeStrategy.SKIP,
                List.of(item("mysql.pdf", title, "PDF", "application/pdf",
                        "objects/mysql.pdf", "hash-a", null)));
    }

    private IngestionApplicationService.IngestionCreateItemCommand item(String fileName,
                                                                         String title,
                                                                         String fileType,
                                                                         String mimeType,
                                                                         String objectKey,
                                                                         String fileHash,
                                                                         String sourceUrl) {
        return new IngestionApplicationService.IngestionCreateItemCommand(
                fileName, title, fileType, mimeType, 1024L, objectKey, fileHash, sourceUrl);
    }

    private IngestionTask task(String taskId, String kbId, String clientRequestId, String requestHash) {
        return IngestionTask.builder()
                .id(taskId)
                .kbId(kbId)
                .sourceType(IngestionSourceType.UPLOAD)
                .clientRequestId(clientRequestId)
                .requestHash(requestHash)
                .status(IngestionTaskStatus.PENDING)
                .createdBy("user-a")
                .updatedBy("user-a")
                .items(List.of())
                .build();
    }

    private IngestionTaskItem failedItem(String itemId, int parseAttempt, String doclingRequestId) {
        return IngestionTaskItem.builder()
                .id(itemId)
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-" + itemId)
                .fileName(itemId + ".pdf")
                .parseAttempt(parseAttempt)
                .doclingRequestId(doclingRequestId)
                .sourceRevision("v1:" + "a".repeat(64))
                .status(IngestionTaskItemStatus.FAILED)
                .build();
    }

    private Asset existingAsset(String assetId, String fileHash) {
        return Asset.builder()
                .id(assetId)
                .kbId("kb-1")
                .fileName("existing.pdf")
                .fileType("PDF")
                .fileHash(fileHash)
                .sourceUrl("oss://existing.pdf")
                .versionNo(1)
                .build();
    }
}
