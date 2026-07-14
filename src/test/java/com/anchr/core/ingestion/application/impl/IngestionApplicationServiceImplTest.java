package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
                ingestionTaskProcessor
        );
        nextId = 1000L;
        when(idGen.nextIdStr()).thenAnswer(invocation -> String.valueOf(nextId++));
        when(ingestionTaskRepository.findById(eq("kb-1"), any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
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

        IngestionTask task = service.createTask("kb-1", command(null, IngestionSourceType.UPLOAD, "hash-a"));

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

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-a"));

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

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.UPLOAD, "hash-new"));

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.NEW);
        assertThat(item.getDedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
        assertThat(item.getDuplicateAssetId()).isNull();
        verify(assetRepository).save(any());
    }

    @Test
    void createTask_shouldMarkOverwriteOnlyWhenDuplicateExists() {
        Asset existing = existingAsset("asset-old", "hash-a");
        when(assetRepository.findActiveByHash("kb-1", "hash-a")).thenReturn(Optional.of(existing));

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.OVERWRITE, IngestionSourceType.UPLOAD, "hash-a"));

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

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.OVERWRITE, IngestionSourceType.UPLOAD, "hash-new"));

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

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.VERSIONED, IngestionSourceType.UPLOAD, "hash-a"));

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

        IngestionTask task = service.createTask("kb-1", command(DedupeStrategy.SKIP, IngestionSourceType.URL, "hash-url"));

        IngestionTaskItem item = task.getItems().getFirst();
        assertThat(item.getStatus()).isEqualTo(IngestionTaskItemStatus.SKIPPED);
        assertThat(item.getDedupeResult()).isEqualTo(DedupeResult.SKIPPED);
        assertThat(item.getAssetId()).isEqualTo("asset-url");
    }

    private IngestionApplicationService.IngestionCreateCommand command(DedupeStrategy strategy,
                                                                       IngestionSourceType sourceType,
                                                                       String fileHash) {
        return new IngestionApplicationService.IngestionCreateCommand(
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
