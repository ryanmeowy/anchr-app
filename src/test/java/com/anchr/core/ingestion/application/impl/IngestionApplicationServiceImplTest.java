package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.application.constant.IngestionConstant;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private IngestionActivityAcl activityEventService;
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
        IngestionTaskQuery taskQuery = new IngestionTaskQuery(
                knowledgeBaseService,
                ingestionTaskRepository);
        IngestionTaskCreateUseCase createUseCase = new IngestionTaskCreateUseCase(
                knowledgeBaseService,
                assetRepository,
                knowledgeBaseRepository,
                ingestionTaskRepository,
                new IngestionCapabilityService(),
                idGen,
                activityEventService,
                ingestionTaskProcessor,
                transactionRunner,
                taskQuery);
        IngestionTaskMaintenanceUseCase maintenanceUseCase = new IngestionTaskMaintenanceUseCase(
                knowledgeBaseService,
                assetRepository,
                ingestionTaskRepository,
                idGen,
                activityEventService,
                ingestionTaskProcessor,
                taskQuery);
        service = new IngestionApplicationServiceImpl(createUseCase, maintenanceUseCase, taskQuery);
        lenient().when(transactionRunner.write(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(transactionRunner.read(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        nextId = 1000L;
        lenient().when(idGen.nextIdStr()).thenAnswer(invocation -> String.valueOf(nextId++));
        lenient().when(ingestionTaskRepository.findById(eq("kb-1"), any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        lenient().when(assetRepository.findByIdForUpdate(eq("kb-1"), any()))
                .thenAnswer(invocation -> Optional.of(Asset.builder()
                        .id(invocation.getArgument(1))
                        .kbId("kb-1")
                        .activeIndexGeneration(1L)
                        .build()));
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
        assertThat(item.getTargetIndexGeneration()).isEqualTo(1L);
        verify(assetRepository).save(any());
    }

    @Test
    void createTask_shouldRejectRegularFileLargerThanBackendLimit() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                service.createTask("kb-1", command(
                        DedupeStrategy.SKIP,
                        IngestionSourceType.UPLOAD,
                        "PDF",
                        IngestionConstant.MAX_FILE_SIZE_BYTES + 1)));

        assertThat(error.getError()).isEqualTo(ApiError.UPLOAD_TOO_LARGE);
        verifyNoInteractions(knowledgeBaseService);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createTask_shouldUseSeparateImageFileLimit() {
        long imageSize = IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES;

        IngestionTask task = service.createTask("kb-1", command(
                DedupeStrategy.SKIP,
                IngestionSourceType.UPLOAD,
                "IMAGE",
                imageSize)).task();

        assertThat(task.getItems()).hasSize(1);
        verify(assetRepository).save(argThat(asset -> asset.getSizeBytes().equals(imageSize)));
    }

    @Test
    void createTask_shouldRejectImageLargerThanBackendImageLimit() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                service.createTask("kb-1", command(
                        DedupeStrategy.SKIP,
                        IngestionSourceType.UPLOAD,
                        "IMAGE",
                        IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES + 1)));

        assertThat(error.getError()).isEqualTo(ApiError.UPLOAD_TOO_LARGE);
        verifyNoInteractions(knowledgeBaseService);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createTask_shouldRejectNegativeDeclaredFileSize() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                service.createTask("kb-1", command(
                        DedupeStrategy.SKIP,
                        IngestionSourceType.UPLOAD,
                        "PDF",
                        -1L)));

        assertThat(error.getError()).isEqualTo(ApiError.INVALID_REQUEST);
        verifyNoInteractions(knowledgeBaseService);
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
    }

    @Test
    void genericCreateTask_shouldRejectMaintenanceSourceValues() {
        for (IngestionSourceType sourceType : List.of(
                IngestionSourceType.REPARSE,
                IngestionSourceType.REEMBED,
                IngestionSourceType.RETRY)) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    service.createTask(
                            "kb-1",
                            command(DedupeStrategy.SKIP, sourceType, "hash-" + sourceType)));
            assertThat(error.getError()).isEqualTo(ApiError.INVALID_REQUEST);
        }
        verifyNoInteractions(assetRepository);
    }

    @Test
    void createMaintenanceTasks_shouldUseSourceSpecificPendingProjections() {
        Asset document = existingAsset("asset-1", "hash-a").toBuilder()
                .parseStatus(DocumentParseStatus.SUCCESS)
                .build();
        when(knowledgeBaseService.getDocument("kb-1", "asset-1"))
                .thenReturn(document);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(document));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-1"))
                .thenReturn(0L, 1L);

        IngestionTask reparse = service.createReparseTask("kb-1", "asset-1");
        assertThat(reparse.getItems().getFirst().getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(reparse.getItems().getFirst().getStatus())
                .isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(reparse.getItems().getFirst().getProgress()).isEqualTo(20);
        assertThat(reparse.getItems().getFirst().getTargetIndexGeneration())
                .isEqualTo(1L);
        assertThat(savedTask.get().getSourceType())
                .isEqualTo(IngestionSourceType.REPARSE);

        IngestionTask reembed = service.createReembedTask("kb-1", "asset-1");
        assertThat(reembed.getItems().getFirst().getStage()).isEqualTo(IngestionStage.EMBED);
        assertThat(reembed.getItems().getFirst().getStatus())
                .isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(reembed.getItems().getFirst().getProgress()).isEqualTo(60);
        assertThat(reembed.getItems().getFirst().getTargetIndexGeneration())
                .isEqualTo(2L);
        assertThat(savedTask.get().getSourceType())
                .isEqualTo(IngestionSourceType.REEMBED);
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
    void createTask_shouldSubmitOnlyOnceAfterCommitWhenSynchronizationIsActive() {
        IngestionApplicationService.IngestionCreateCommand command =
                command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-new");
        TransactionSynchronizationManager.initSynchronization();
        try {
            IngestionTask task = service.createTask("kb-1", command).task();

            verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.getFirst().afterCommit();

            verify(ingestionTaskProcessor).submit("kb-1", task.getId(), "user-a");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
                        " objects/mysql.pdf ", " hash-a ")));
        var replayCommand = new IngestionApplicationService.IngestionCreateCommand(
                "request-1",
                IngestionSourceType.UPLOAD,
                DedupeStrategy.SKIP,
                List.of(item("mysql.pdf", "MySQL", "PDF", "application/pdf",
                        "objects/mysql.pdf", "hash-a")));

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
        verify(activityEventService).recordDocumentImported(any());
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
        var firstItem = item("a.pdf", "A", "PDF", "application/pdf", "objects/a", "hash-a");
        var secondItem = item("b.pdf", "B", "PDF", "application/pdf", "objects/b", "hash-b");
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
        verify(activityEventService, never()).recordDocumentImported(any());
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
    void listTasks_shouldKeepDefaultMaximumAndExplicitLimits() {
        service.listTasks("kb-1", IngestionTaskStatus.PENDING, 0);
        service.listTasks("kb-1", IngestionTaskStatus.PENDING, 101);
        service.listTasks("kb-1", IngestionTaskStatus.PENDING, 7);

        verify(ingestionTaskRepository).list("kb-1", IngestionTaskStatus.PENDING, 20);
        verify(ingestionTaskRepository).list("kb-1", IngestionTaskStatus.PENDING, 100);
        verify(ingestionTaskRepository).list("kb-1", IngestionTaskStatus.PENDING, 7);
    }

    @Test
    void retryItem_shouldAllocateANewTargetGeneration() {
        IngestionTaskItem failedItem = failedItem("item-1", 3);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.findRetryItem("kb-1", "task-1", "item-1"))
                .thenReturn(Optional.of(failedItem));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-1"))
                .thenReturn(3L);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(4L),
                any(LocalDateTime.class)))
                .thenReturn(true);

        service.retryItem("kb-1", "task-1", "item-1");

        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(4L),
                any(LocalDateTime.class));
        verify(ingestionTaskRepository)
                .refreshSummary(eq("kb-1"), eq("task-1"), eq("user-a"), any(LocalDateTime.class));
        verify(ingestionTaskProcessor).submit("kb-1", "task-1", "user-a");
    }

    @Test
    void retryFailed_whenLockedSetIsEmpty_shouldRejectWithoutWritesOrScheduling() {
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem("item-1", 1)))
                .build());
        when(ingestionTaskRepository.listRetryItemsForUpdate("kb-1", "task-1"))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retryFailed("kb-1", "task-1"));

        assertThat(error.getError()).isEqualTo(ApiError.INGEST_NO_FAILED_ITEMS);
        verify(ingestionTaskRepository, never()).resetFailedItem(
                any(), any(), any(), anyLong(), any());
        verify(ingestionTaskRepository, never()).refreshSummary(any(), any(), any(), any());
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    @Test
    void retryFailed_whenAssetWasDeleted_shouldRejectWithoutWritesOrScheduling() {
        IngestionTaskItem failedItem = failedItem("item-1", 1);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.listRetryItemsForUpdate("kb-1", "task-1"))
                .thenReturn(List.of(failedItem));
        when(assetRepository.findByIdForUpdate("kb-1", "asset-item-1"))
                .thenReturn(Optional.of(Asset.builder()
                        .id("asset-item-1")
                        .kbId("kb-1")
                        .deletedAt(LocalDateTime.now())
                        .build()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retryFailed("kb-1", "task-1"));

        assertThat(error.getError()).isEqualTo(ApiError.DOCUMENT_NOT_FOUND);
        verify(ingestionTaskRepository, never()).resetFailedItem(
                any(), any(), any(), anyLong(), any());
        verify(ingestionTaskRepository, never()).refreshSummary(any(), any(), any(), any());
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    @Test
    void retryFailed_whenLaterItemChanges_shouldNotRefreshOrSchedule() {
        IngestionTaskItem first = failedItem("item-first", 1);
        IngestionTaskItem changed = failedItem("item-changed", 2);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(first, changed))
                .build());
        when(ingestionTaskRepository.listRetryItemsForUpdate("kb-1", "task-1"))
                .thenReturn(List.of(first, changed));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-first"))
                .thenReturn(1L);
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-changed"))
                .thenReturn(2L);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-first"), eq(2L), any()))
                .thenReturn(true);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-changed"), eq(3L), any()))
                .thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retryFailed("kb-1", "task-1"));

        assertThat(error.getError()).isEqualTo(ApiError.INGEST_RETRY_ONLY_FAILED);
        verify(ingestionTaskRepository, never()).refreshSummary(any(), any(), any(), any());
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    @Test
    void retryItem_shouldSubmitOnlyOnceAfterCommitWhenSynchronizationIsActive() {
        IngestionTaskItem failedItem = failedItem("item-1", 3);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.findRetryItem("kb-1", "task-1", "item-1"))
                .thenReturn(Optional.of(failedItem));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-1"))
                .thenReturn(3L);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(4L),
                any(LocalDateTime.class)))
                .thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.retryItem("kb-1", "task-1", "item-1");

            verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.getFirst().afterCommit();

            verify(ingestionTaskProcessor).submit("kb-1", "task-1", "user-a");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retryItem_preflightFailureWithoutAssetShouldBeRejectedBeforeScheduling() {
        IngestionTaskItem preflightFailure = IngestionTaskItem.builder()
                .id("item-unsupported")
                .taskId("task-1")
                .kbId("kb-1")
                .fileName("unsupported.exe")
                .status(IngestionTaskItemStatus.FAILED)
                .errorCode("UNSUPPORTED_FILE_TYPE")
                .build();
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(preflightFailure))
                .build());
        when(ingestionTaskRepository.findRetryItem(
                "kb-1", "task-1", "item-unsupported")).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retryItem("kb-1", "task-1", "item-unsupported"));

        assertThat(error.getError()).isEqualTo(ApiError.INGEST_RETRY_ONLY_FAILED);
        verify(ingestionTaskRepository, never()).resetFailedItem(
                any(), any(), any(), anyLong(), any());
        verify(ingestionTaskProcessor, never()).submit(any(), any(), any());
    }

    @Test
    void retryFailed_shouldAllocateNewGenerationForEveryFailedItem() {
        IngestionTaskItem legacyItem = failedItem("item-legacy", 1);
        IngestionTaskItem currentItem = failedItem("item-current", 4);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(legacyItem, currentItem))
                .build());
        when(ingestionTaskRepository.listRetryItemsForUpdate("kb-1", "task-1"))
                .thenReturn(List.of(legacyItem, currentItem));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-legacy"))
                .thenReturn(1L);
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-current"))
                .thenReturn(4L);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-legacy"), eq(2L),
                any(LocalDateTime.class)))
                .thenReturn(true);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-current"), eq(5L),
                any(LocalDateTime.class)))
                .thenReturn(true);

        service.retryFailed("kb-1", "task-1");

        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-legacy"), eq(2L),
                any(LocalDateTime.class));
        verify(ingestionTaskRepository).resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-current"), eq(5L),
                any(LocalDateTime.class));
        verify(ingestionTaskRepository)
                .refreshSummary(eq("kb-1"), eq("task-1"), eq("user-a"), any(LocalDateTime.class));
        verify(ingestionTaskProcessor).submit("kb-1", "task-1", "user-a");
    }

    @Test
    void retryItem_whenStatusChanges_shouldRejectWithoutSchedulingProcessor() {
        IngestionTaskItem failedItem = failedItem("item-1", 2);
        savedTask.set(task("task-1", "kb-1", null, null).toBuilder()
                .status(IngestionTaskStatus.FAILED)
                .items(List.of(failedItem))
                .build());
        when(ingestionTaskRepository.findRetryItem("kb-1", "task-1", "item-1"))
                .thenReturn(Optional.of(failedItem));
        when(ingestionTaskRepository.findMaxTargetIndexGeneration("asset-item-1"))
                .thenReturn(2L);
        when(ingestionTaskRepository.resetFailedItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(3L),
                any(LocalDateTime.class)))
                .thenReturn(false);

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
                        fileHash
                ))
        );
    }

    private IngestionApplicationService.IngestionCreateCommand command(DedupeStrategy strategy,
                                                                       IngestionSourceType sourceType,
                                                                       String fileType,
                                                                       long sizeBytes) {
        return new IngestionApplicationService.IngestionCreateCommand(
                null,
                sourceType,
                strategy,
                List.of(new IngestionApplicationService.IngestionCreateItemCommand(
                        "upload." + fileType.toLowerCase(),
                        "Upload",
                        fileType,
                        "IMAGE".equals(fileType) ? "image/png" : "application/pdf",
                        sizeBytes,
                        "objects/upload",
                        "hash-size"
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
                        "objects/mysql.pdf", "hash-a")));
    }

    private IngestionApplicationService.IngestionCreateItemCommand item(String fileName,
                                                                         String title,
                                                                         String fileType,
                                                                         String mimeType,
                                                                         String objectKey,
                                                                         String fileHash) {
        return new IngestionApplicationService.IngestionCreateItemCommand(
                fileName, title, fileType, mimeType, 1024L, objectKey, fileHash);
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

    private IngestionTaskItem failedItem(String itemId, int targetGeneration) {
        return IngestionTaskItem.builder()
                .id(itemId)
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-" + itemId)
                .targetIndexGeneration((long) targetGeneration)
                .fileName(itemId + ".pdf")
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
                .objectKey("objects/existing.pdf")
                .versionNo(1)
                .build();
    }
}
