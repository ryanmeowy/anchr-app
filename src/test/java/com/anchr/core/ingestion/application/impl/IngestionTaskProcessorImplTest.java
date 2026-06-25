package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskProcessorImplTest {

    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private IngestionEmbeddingPort embeddingPort;
    @Mock
    private AesUtil aesUtil;
    @Mock
    private SegmentBulkWriter segmentBulkWriter;
    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private IngestionObjectStoragePort objectStoragePort;
    @Mock
    private StorageConfigRepository storageConfigRepository;
    @Mock
    private DoclingChunkMapper doclingChunkMapper;

    private IngestionTaskProcessorImpl processor;

    @BeforeEach
    void setUp() {
        processor = new IngestionTaskProcessorImpl(
                Runnable::run,
                ingestionTaskRepository,
                assetRepository,
                knowledgeBaseRepository,
                embeddingPort,
                aesUtil,
                segmentBulkWriter,
                segmentRepository,
                objectStoragePort,
                storageConfigRepository,
                doclingChunkMapper,
                new Gson()
        );
    }

    @Test
    void cleanupOverwrittenAsset_shouldDeleteOldAssetAndSegments() throws Exception {
        IngestionTaskItem item = overwrittenItem("asset-new", "asset-old");
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-old"), eq("user-a"), any(LocalDateTime.class)))
                .thenReturn(true);

        invokeCleanup(item);

        verify(assetRepository).markDeleted(eq("kb-1"), eq("asset-old"), eq("user-a"), any(LocalDateTime.class));
        verify(segmentRepository).deleteByAssetId("asset-old");
    }

    @Test
    void cleanupOverwrittenAsset_shouldSkipWhenItemIsNotOverwritten() throws Exception {
        IngestionTaskItem item = overwrittenItem("asset-new", "asset-old").toBuilder()
                .dedupeResult(DedupeResult.NEW)
                .build();

        invokeCleanup(item);

        verify(assetRepository, never()).markDeleted(any(), any(), any(), any());
        verify(segmentRepository, never()).deleteByAssetId(any());
    }

    private void invokeCleanup(IngestionTaskItem item) throws Exception {
        Method method = IngestionTaskProcessorImpl.class.getDeclaredMethod(
                "cleanupOverwrittenAsset", String.class, IngestionTaskItem.class, String.class);
        method.setAccessible(true);
        method.invoke(processor, "kb-1", item, "user-a");
    }

    private IngestionTaskItem overwrittenItem(String assetId, String duplicateAssetId) {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId(assetId)
                .fileName("mysql.pdf")
                .stage(IngestionStage.ASKABLE)
                .status(IngestionTaskItemStatus.SUCCESS)
                .progress(100)
                .dedupeResult(DedupeResult.OVERWRITTEN)
                .duplicateAssetId(duplicateAssetId)
                .build();
    }
}
