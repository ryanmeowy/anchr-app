package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.ingestion.application.acl.IngestionDoclingAcl;
import com.anchr.core.ingestion.application.acl.IngestionRetrievalAcl;
import com.anchr.core.ingestion.application.acl.IngestionStorageAcl;
import com.anchr.core.ingestion.application.model.IngestionDoclingException;
import com.anchr.core.ingestion.application.model.IngestionDoclingFailureKind;
import com.anchr.core.ingestion.application.model.IngestionDoclingJob;
import com.anchr.core.ingestion.application.model.IngestionDoclingJobError;
import com.anchr.core.ingestion.application.model.IngestionStorageCredential;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskProcessorImplTest {

    @Mock private IngestionTaskRepository repository;
    @Mock private AssetRepository assetRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private IngestionEmbeddingPort embeddingPort;
    @Mock private AesUtil aesUtil;
    @Mock private IngestionIndexFinalizer finalizer;
    @Mock private IngestionRetrievalAcl ingestionRetrievalAcl;
    @Mock private IngestionStageTransactionCoordinator coordinator;
    @Mock private IngestionObjectStoragePort objectStoragePort;
    @Mock private IngestionStorageAcl ingestionStorageAcl;
    @Mock private DoclingChunkMapper chunkMapper;
    @Mock private IngestionDoclingAcl ingestionDoclingAcl;
    @Mock private IdGen idGen;

    private IngestionTaskProcessorImpl processor;

    @BeforeEach
    void setUp() {
        processor = newProcessor(Runnable::run);
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
        when(ingestionDoclingAcl.submitJob(any(), anyInt())).thenReturn(
                new IngestionDoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text")).thenReturn(List.of(0.1f));
        when(ingestionRetrievalAcl.replaceGeneration(any(), eq(asset), any()))
                .thenReturn(receipt());
        when(finalizer.activateGeneration(any(), eq(asset), eq(1), eq(receipt())))
                .thenReturn(true);

        processor.processItem(item);

        var order = org.mockito.Mockito.inOrder(
                coordinator,
                ingestionDoclingAcl,
                chunkMapper,
                embeddingPort,
                ingestionRetrievalAcl,
                finalizer,
                knowledgeBaseRepository);
        order.verify(coordinator).ensureTargetIndexGeneration(item);
        order.verify(coordinator).updateAssetStatus(any(), eq(asset), any(), any());
        order.verify(ingestionDoclingAcl).submitJob(any(), anyInt());
        order.verify(coordinator).advanceAndUpdateAssetStatus(
                any(), eq(IngestionStage.EMBED), eq(55), eq(asset), any(), any());
        order.verify(chunkMapper).toTextChunks(asset, response, 1L);
        order.verify(chunkMapper).toDocumentImageChunks(asset, response, 1L);
        order.verify(embeddingPort).isMulti();
        order.verify(embeddingPort).embed("hello", "text");
        order.verify(coordinator).advanceAndUpdateAssetStatus(
                any(), eq(IngestionStage.INDEX), eq(75), eq(asset), any(), any());
        order.verify(ingestionRetrievalAcl).replaceGeneration(any(), eq(asset), any());
        order.verify(finalizer).activateGeneration(any(), eq(asset), eq(1), eq(receipt()));
        order.verify(ingestionDoclingAcl).ackJob("job-1");
        order.verify(knowledgeBaseRepository)
                .refreshDocumentStats("kb-1", "user-1", true);
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
        when(ingestionDoclingAcl.submitJob(any(), anyInt()))
                .thenReturn(new IngestionDoclingJob(
                        "failed", "task-1:item-1:1", "failed", null,
                        new IngestionDoclingJobError("INTERNAL_ERROR", "retry")))
                .thenReturn(new IngestionDoclingJob(
                        "job-2", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of());
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of(
                Chunk.builder().segmentId("image-1").kbId("kb-1")
                        .assetId("asset-1").segmentType(SegmentType.DOCUMENT_IMAGE)
                        .sourceRef("images/1.png").build()));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(ingestionRetrievalAcl.replaceGeneration(any(), eq(asset), any()))
                .thenReturn(receipt());
        when(finalizer.activateGeneration(any(), eq(asset), eq(1), eq(receipt())))
                .thenReturn(true);

        processor.processItem(item);

        verify(ingestionDoclingAcl, org.mockito.Mockito.times(2))
                .submitJob(any(), anyInt());
        verify(ingestionDoclingAcl).ackJob("failed");
        verify(ingestionRetrievalAcl).replaceGeneration(any(), eq(asset), any());
        verify(finalizer).activateGeneration(any(), eq(asset), eq(1), eq(receipt()));
    }

    @Test
    void processItem_whenDoclingReturnsTransientRetryAfter_shouldRetryWithinSameRun() {
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
        when(ingestionDoclingAcl.submitJob(any(), anyInt()))
                .thenThrow(new IngestionDoclingException(
                        IngestionDoclingFailureKind.TRANSIENT,
                        429,
                        Duration.ofMillis(1),
                        "busy",
                        null))
                .thenReturn(new IngestionDoclingJob(
                        "job-2", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text")).thenReturn(List.of(0.1f));
        when(ingestionRetrievalAcl.replaceGeneration(any(), eq(asset), any()))
                .thenReturn(receipt());
        when(finalizer.activateGeneration(any(), eq(asset), eq(1), eq(receipt())))
                .thenReturn(true);

        processor.processItem(item);

        verify(ingestionDoclingAcl, times(2)).submitJob(any(), anyInt());
        verify(ingestionDoclingAcl).ackJob("job-2");
        verify(finalizer).activateGeneration(any(), eq(asset), eq(1), eq(receipt()));
    }

    @Test
    void processItem_whenRetrievalWriteFails_shouldFailItemWithoutActivatingGeneration() {
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
        when(ingestionDoclingAcl.submitJob(any(), anyInt())).thenReturn(
                new IngestionDoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text")).thenReturn(List.of(0.1f));
        when(ingestionRetrievalAcl.replaceGeneration(any(), eq(asset), any()))
                .thenThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE));

        processor.processItem(item);

        verify(finalizer, never()).activateGeneration(
                any(), any(), anyInt(), any());
        verify(coordinator).failRunning(
                org.mockito.ArgumentMatchers.argThat(failed ->
                        failed.getStage() == IngestionStage.INDEX),
                eq(asset), eq(ApiError.SEARCH_BACKEND_UNAVAILABLE),
                any(), eq("FAILED"), eq("FAILED"));
        verify(ingestionDoclingAcl, never()).ackJob("job-1");
    }

    @Test
    void processItem_whenEmbeddingProviderFails_shouldKeepEmbedStageAndErrorMapping() {
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
                any(), eq(IngestionStage.EMBED), eq(55), eq(asset), any(), any()))
                .thenReturn(true);
        when(objectStoragePort.buildDownloadUrl("objects/a.pdf")).thenReturn("https://source");
        when(ingestionDoclingAcl.submitJob(any(), anyInt())).thenReturn(
                new IngestionDoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L)).thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L)).thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text"))
                .thenThrow(new IllegalStateException("provider unavailable"));
        when(coordinator.failRunning(
                any(), eq(asset), eq(ApiError.EMBEDDING_FAILED), any(), any(), any()))
                .thenReturn(true);

        processor.processItem(item);

        verify(coordinator).failRunning(
                org.mockito.ArgumentMatchers.argThat(failed ->
                        failed.getStage() == IngestionStage.EMBED),
                eq(asset),
                eq(ApiError.EMBEDDING_FAILED),
                eq("provider unavailable"),
                eq("FAILED"),
                eq("FAILED"));
        verify(ingestionRetrievalAcl, never()).replaceGeneration(any(), any(), any());
        verify(finalizer, never()).activateGeneration(any(), any(), anyInt(), any());
        verify(ingestionDoclingAcl).ackJob("job-1");
    }

    @Test
    void processItem_withEmbeddedImages_shouldKeepTargetAadAndCredentialEnvelope() {
        processor = newProcessor(
                Runnable::run,
                RuntimeConfigTestUnits.values(Map.of(
                        "INGESTION.parsePollIntervalSeconds", "0",
                        "INGESTION.parseStageTimeoutMinutes", "1",
                        "INGESTION.stageMaxRetries", "2",
                        "INGESTION.embeddingMinIntervalMs", "0",
                        "INGESTION.chunkMinTokens", "300",
                        "INGESTION.chunkMaxTokens", "900",
                        "INGESTION.embeddedImageUploadEnabled", "true")));
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
        IngestionStorageTarget target = new IngestionStorageTarget(
                "https://oss",
                "bucket",
                "embedded/ingestion/assets/asset-1/generations/1/images/",
                IngestionImagePaths.DOCLING_OBJECT_KEY_LAYOUT);
        when(coordinator.ensureTargetIndexGeneration(item)).thenReturn(item);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(coordinator.updateAssetStatus(any(), eq(asset), any(), any()))
                .thenReturn(true);
        when(coordinator.advanceAndUpdateAssetStatus(
                any(), any(), anyInt(), eq(asset), any(), any()))
                .thenReturn(true);
        when(ingestionStorageAcl.findTarget("asset-1", 1L))
                .thenReturn(Optional.of(target));
        when(ingestionStorageAcl.issueTemporaryCredential(
                target, "asset-1", 1L)).thenReturn(
                new IngestionStorageCredential(
                        "https://oss", "bucket", "cn-test", "embedded/",
                        "temp-ak", "temp-sk", "token", "expiry"));
        when(aesUtil.encryptAead(
                any(),
                eq("task-1:item-1:1\nbucket\n"
                        + "embedded/ingestion/assets/asset-1/generations/1/images/\n"
                        + "https://oss")))
                .thenReturn(new AesUtil.AeadEnvelope("nonce", "cipher", "tag"));
        when(objectStoragePort.buildDownloadUrl("objects/a.pdf"))
                .thenReturn("https://source");
        when(ingestionDoclingAcl.submitJob(any(), anyInt())).thenReturn(
                new IngestionDoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(chunkMapper.toTextChunks(asset, response, 1L))
                .thenReturn(List.of(chunk));
        when(chunkMapper.toDocumentImageChunks(asset, response, 1L))
                .thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("hello", "text")).thenReturn(List.of(0.1f));
        when(ingestionRetrievalAcl.replaceGeneration(any(), eq(asset), any()))
                .thenReturn(receipt());
        when(finalizer.activateGeneration(any(), eq(asset), eq(1), eq(receipt())))
                .thenReturn(true);
        org.mockito.ArgumentCaptor<com.anchr.core.common.model.ParseRequest> request =
                org.mockito.ArgumentCaptor.forClass(
                        com.anchr.core.common.model.ParseRequest.class);

        processor.processItem(item);

        verify(ingestionDoclingAcl).submitJob(request.capture(), anyInt());
        assertThat(request.getValue().contractVersion()).isEqualTo(3);
        assertThat(request.getValue().options().chunkMinTokens()).isEqualTo(300);
        assertThat(request.getValue().options().chunkMaxTokens()).isEqualTo(900);
        assertThat(request.getValue().oss().endpoint()).isEqualTo("https://oss");
        assertThat(request.getValue().oss().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().oss().basePath()).isEqualTo(
                "embedded/ingestion/assets/asset-1/generations/1/images/");
        assertThat(request.getValue().oss().encryptedCredentials())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "version", "1",
                        "keyId", "app-security-v1",
                        "nonce", "nonce",
                        "ciphertext", "cipher",
                        "tag", "tag",
                        "expiration", "expiry"));
        var order = org.mockito.Mockito.inOrder(
                ingestionStorageAcl, ingestionDoclingAcl);
        order.verify(ingestionStorageAcl)
                .issueTemporaryCredential(target, "asset-1", 1L);
        order.verify(ingestionDoclingAcl).submitJob(any(), anyInt());
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
        verify(ingestionDoclingAcl, never()).submitJob(any(), anyInt());
    }

    @Test
    void submit_whenExecutorRejects_shouldReleaseLocalDispatchForTheNextWakeUp() {
        AtomicInteger attempts = new AtomicInteger();
        IngestionTaskProcessorImpl rejectingProcessor = newProcessor(command -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("full");
        });
        when(repository.listPendingItemIds("task-1", 32))
                .thenReturn(List.of("item-1"));

        rejectingProcessor.submit("kb-1", "task-1", "user-1");
        rejectingProcessor.submit("kb-1", "task-1", "user-1");

        assertThat(attempts).hasValue(2);
        verify(repository, never()).claimPending(any());
    }

    @Test
    void submit_shouldLocallyDeduplicateTheSamePendingItemBeforeExecution() {
        AtomicInteger accepted = new AtomicInteger();
        IngestionTaskProcessorImpl holdingProcessor =
                newProcessor(command -> accepted.incrementAndGet());
        when(repository.listPendingItemIds("task-1", 32))
                .thenReturn(List.of("item-1", "item-1"));

        holdingProcessor.submit("kb-1", "task-1", "user-1");

        assertThat(accepted).hasValue(1);
    }

    private IngestionTaskProcessorImpl newProcessor(Executor executor) {
        return newProcessor(executor, RuntimeConfigTestUnits.values(Map.of(
                "INGESTION.parsePollIntervalSeconds", "0",
                "INGESTION.parseStageTimeoutMinutes", "1",
                "INGESTION.stageMaxRetries", "2",
                "INGESTION.embeddingMinIntervalMs", "0")));
    }

    private IngestionTaskProcessorImpl newProcessor(
            Executor executor,
            RuntimeConfigUnit runtimeConfigUnit) {
        return new IngestionTaskProcessorImpl(
                executor, repository, assetRepository, knowledgeBaseRepository,
                embeddingPort, aesUtil, finalizer,
                ingestionRetrievalAcl, coordinator,
                objectStoragePort, ingestionStorageAcl, chunkMapper, ingestionDoclingAcl,
                new ObjectMapper(), idGen, runtimeConfigUnit);
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

    private RetrievalGenerationWriteReceipt receipt() {
        return new RetrievalGenerationWriteReceipt(
                "kb-1", "asset-1", 1L, 1, "kb_segment_write", "profile");
    }
}
