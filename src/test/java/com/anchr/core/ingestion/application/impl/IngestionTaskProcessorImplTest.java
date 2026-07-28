package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskProcessorImplTest {

    @Mock private IngestionTaskRepository repository;
    @Mock private AssetRepository assetRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private IngestionEmbeddingPort embeddingPort;
    @Mock private AesUtil aesUtil;
    @Mock private StorageTokenIssuer storageTokenIssuer;
    @Mock private IngestionIndexFinalizer finalizer;
    @Mock private IngestionStageTransactionCoordinator coordinator;
    @Mock private IngestionObjectStoragePort objectStoragePort;
    @Mock private StorageConfigRepository storageConfigRepository;
    @Mock private DoclingChunkMapper chunkMapper;
    @Mock private DoclingClient doclingClient;
    @Mock private IdGen idGen;

    private IngestionTaskProcessorImpl processor;

    @BeforeEach
    void setUp() {
        Executor direct = Runnable::run;
        processor = new IngestionTaskProcessorImpl(
                direct, repository, assetRepository, knowledgeBaseRepository,
                embeddingPort, aesUtil, storageTokenIssuer, finalizer, coordinator,
                objectStoragePort, storageConfigRepository, chunkMapper, doclingClient,
                new ObjectMapper(), idGen);
        ReflectionTestUtils.setField(processor, "parsePollInterval", Duration.ofMillis(1));
        ReflectionTestUtils.setField(processor, "parseTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(processor, "providerMaxRetries", 2);
        ReflectionTestUtils.setField(processor, "embeddingMinIntervalMs", 0L);
        ReflectionTestUtils.setField(processor, "embeddedImageUploadEnabled", false);
    }

    @Test
    void processItem_shouldRunWholeDocumentToIndexWithoutPersistingProviderState() {
        IngestionTaskItem item = runningItem();
        Asset asset = asset();
        ParseResponse response = response();
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("hello")
                .segmentType(SegmentType.TEXT_CHUNK)
                .build();

        when(coordinator.ensureTargetIndexGeneration(item)).thenReturn(item);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(coordinator.updateAssetStatus(any(), eq(asset), any(), any())).thenReturn(true);
        when(coordinator.advanceAndUpdateAssetStatus(
                any(), any(), anyInt(), eq(asset), any(), any())).thenReturn(true);
        when(objectStoragePort.buildDownloadUrl("objects/a.pdf")).thenReturn("https://source");
        when(doclingClient.submitJob(any())).thenReturn(
                new DoclingClient.DoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text")).thenReturn(List.of(0.1f));
        when(finalizer.finalizeIndex(any(), eq(asset), any())).thenReturn(true);

        processor.processItem(item);

        verify(coordinator).advanceAndUpdateAssetStatus(
                any(), eq(IngestionStage.EMBED), eq(55), eq(asset), any(), any());
        verify(coordinator).advanceAndUpdateAssetStatus(
                any(), eq(IngestionStage.INDEX), eq(75), eq(asset), any(), any());
        verify(finalizer).finalizeIndex(any(), eq(asset), any());
        verify(doclingClient).ackJob("job-1");
        verify(repository, never()).advanceRunningItem(any(), any(), any(), any(), any(),
                anyInt(), any());
    }

    @Test
    void processItem_whenDoclingFailsRetryably_shouldResubmitWithinSameRun() {
        IngestionTaskItem item = runningItem();
        Asset asset = asset();
        ParseResponse response = response();
        when(coordinator.ensureTargetIndexGeneration(item)).thenReturn(item);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(coordinator.updateAssetStatus(any(), eq(asset), any(), any())).thenReturn(true);
        when(coordinator.advanceAndUpdateAssetStatus(
                any(), any(), anyInt(), eq(asset), any(), any())).thenReturn(true);
        when(objectStoragePort.buildDownloadUrl("objects/a.pdf")).thenReturn("https://source");
        when(doclingClient.submitJob(any()))
                .thenReturn(new DoclingClient.DoclingJob(
                        "failed", "task-1:item-1:1", "failed", null,
                        new DoclingClient.DoclingJobError("INTERNAL_ERROR", "retry")))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-2", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of());
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of(
                Chunk.builder().segmentId("image-1").kbId("kb-1")
                        .assetId("asset-1").segmentType(SegmentType.DOCUMENT_IMAGE)
                        .sourceRef("images/1.png").build()));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(finalizer.finalizeIndex(any(), eq(asset), any())).thenReturn(true);

        processor.processItem(item);

        verify(doclingClient, org.mockito.Mockito.times(2)).submitJob(any());
        verify(doclingClient).ackJob("failed");
        verify(finalizer).finalizeIndex(any(), eq(asset), any());
    }

    @Test
    void startup_shouldFailResidualRunningItemsInsteadOfResumingThem() {
        IngestionTaskItem item = runningItem().toBuilder()
                .stage(IngestionStage.EMBED)
                .progress(55)
                .build();
        Asset asset = asset();
        when(repository.listRunningItems()).thenReturn(List.of(item));
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));

        processor.failInterruptedItemsAfterRestart();

        verify(coordinator).failRunning(
                eq(item), eq(asset), eq(com.anchr.core.common.exception.ApiError.INTERNAL_ERROR),
                any(), eq("FAILED"), eq("FAILED"));
        verify(doclingClient, never()).submitJob(any());
    }

    private IngestionTaskItem runningItem() {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .taskCreatedBy("user-1")
                .assetId("asset-1")
                .targetIndexGeneration(1L)
                .fileName("a.pdf")
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(20)
                .build();
    }

    private Asset asset() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("a.pdf")
                .fileType("PDF")
                .objectKey("objects/a.pdf")
                .build();
    }

    private ParseResponse response() {
        return new ParseResponse(
                "request-1", "docling", "markdown", "hello", "PDF",
                List.of(), List.of(new ParseResponse.Chunk(
                        "chunk-1", "text", "hello", "hello",
                        List.of(1), 5, null, List.of(), List.of())),
                List.of(), List.of());
    }
}
