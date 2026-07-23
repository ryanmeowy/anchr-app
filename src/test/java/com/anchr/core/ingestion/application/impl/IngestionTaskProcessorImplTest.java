package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private IngestionIndexFinalizer ingestionIndexFinalizer;
    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private IngestionObjectStoragePort objectStoragePort;
    @Mock
    private StorageConfigRepository storageConfigRepository;
    @Mock
    private DoclingChunkMapper doclingChunkMapper;
    @Mock
    private DoclingClient doclingClient;

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
                ingestionIndexFinalizer,
                segmentRepository,
                objectStoragePort,
                storageConfigRepository,
                doclingChunkMapper,
                doclingClient,
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

    @Test
    void submit_shouldFailPendingItemWhenAssetWasDeletedBeforeProcessing() {
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(10)
                .build();
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .items(List.of(item))
                .build();
        when(ingestionTaskRepository.findById("kb-1", "task-1"))
                .thenReturn(Optional.of(task));
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.empty());

        processor.submit("kb-1", "task-1", "user-a");

        verify(ingestionTaskRepository).markItemFailed(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("PARSE"), eq(10),
                eq("DOCUMENT_NOT_FOUND"), any(), any());
    }

    @Test
    void submit_shouldFailWhenDoclingChunksCannotBeMapped() {
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(10)
                .build();
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .items(List.of(item))
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = parsedResponse();
        when(ingestionTaskRepository.findById("kb-1", "task-1")).thenReturn(Optional.of(task));
        when(assetRepository.findActiveById("kb-1", "asset-1")).thenReturn(Optional.of(asset));
        when(objectStoragePort.buildDownloadUrl("images/image.png"))
                .thenReturn("https://example.test/image.png");
        when(ingestionTaskRepository.prepareParseAttempt(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(1),
                eq("task-1:item-1:1"), any(), any())).thenReturn(true);
        when(ingestionTaskRepository.recordDoclingJob(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("task-1:item-1:1"),
                eq("job-1"), any())).thenReturn(true);
        when(doclingClient.parse(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<DoclingClient.DoclingJob> accepted = invocation.getArgument(1);
            accepted.accept(new DoclingClient.DoclingJob(
                    "job-1", "task-1:item-1:1", "queued", null, null));
            return parsed;
        });
        when(doclingChunkMapper.toTextChunks(asset, parsed)).thenReturn(List.of());

        processor.submit("kb-1", "task-1", "user-a");

        ArgumentCaptor<ParseRequest> requestCaptor = ArgumentCaptor.forClass(ParseRequest.class);
        verify(doclingClient).parse(requestCaptor.capture(), any());
        ParseRequest request = requestCaptor.getValue();
        assertEquals(2, request.contractVersion());
        assertEquals("task-1:item-1:1", request.requestId());
        assertEquals(IngestionParseIdentity.sourceRevision(asset), request.sourceRevision());
        assertFalse(request.options().includeEmbeddedImages());
        assertNull(request.oss());
        verify(ingestionTaskRepository).recordDoclingJob(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("task-1:item-1:1"),
                eq("job-1"), any());
        verifyNoInteractions(storageConfigRepository, aesUtil);
        verify(ingestionTaskRepository).markItemFailed(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("PARSE"), eq(10),
                eq("TEXT_PARSE_FAILED"), any(), any());
        verifyNoInteractions(embeddingPort, ingestionIndexFinalizer);
    }

    @Test
    void enrichTextEmbeddings_shouldUseOcrTextForImageWithTextModel() throws Exception {
        Asset asset = imageAsset();
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .ocrText("recognized invoice text")
                .build();
        List<Float> embedding = List.of(0.1f, 0.2f);
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("recognized invoice text", "text")).thenReturn(embedding);

        invokeEnrichEmbeddings(asset, List.of(chunk), "https://example.test/image.png");

        assertSame(embedding, chunk.getEmbedding());
        verify(embeddingPort).embed("recognized invoice text", "text");
        verify(embeddingPort, never()).embed(any(), eq("image"));
    }

    @Test
    void enrichTextEmbeddings_shouldWriteOneImageEmbeddingToOneCarrierChunk() throws Exception {
        Asset asset = imageAsset();
        Chunk first = Chunk.builder().segmentId("segment-1").build();
        Chunk second = Chunk.builder().segmentId("segment-2").ocrText("  ").build();
        List<Float> embedding = List.of(0.3f, 0.4f);
        String imageUrl = "https://example.test/image.png";
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed(imageUrl, "image")).thenReturn(embedding);

        invokeEnrichEmbeddings(asset, List.of(first, second), imageUrl);

        assertSame(embedding, first.getEmbedding());
        assertNull(second.getEmbedding());
        verify(embeddingPort, times(1)).embed(imageUrl, "image");
        verify(embeddingPort, never()).embed(any(), eq("text"));
    }

    @Test
    void enrichTextEmbeddings_shouldFailWhenMultiImageEmbeddingIsEmpty() {
        String imageUrl = "https://example.test/image.png";
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed(imageUrl, "image")).thenReturn(List.of());
        Chunk chunk = Chunk.builder().segmentId("segment-1").ocrText("recognized text").build();

        BusinessException error = assertThrows(BusinessException.class,
                () -> invokeEnrichEmbeddings(imageAsset(), List.of(chunk), imageUrl));

        assertEquals(ApiError.EMBEDDING_RESULT_EMPTY, error.getError());
        verify(embeddingPort, times(1)).embed(imageUrl, "image");
    }

    @Test
    void enrichTextEmbeddings_shouldSkipBlankOcrForImageWithTextModel() throws Exception {
        when(embeddingPort.isMulti()).thenReturn(false);
        Chunk chunk = Chunk.builder().segmentId("segment-1").ocrText("  ").build();

        invokeEnrichEmbeddings(
                imageAsset(), List.of(chunk), "https://example.test/image.png");

        assertNull(chunk.getEmbedding());
        verify(embeddingPort, never()).embed(any(), any());
    }

    @Test
    void enrichTextEmbeddings_shouldFailWhenMappedChunksAreEmpty() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> invokeEnrichEmbeddings(
                        imageAsset(), List.of(), "https://example.test/image.png"));

        assertEquals(ApiError.TEXT_PARSE_FAILED, error.getError());
        verify(embeddingPort, never()).embed(any(), any());
    }

    @Test
    void enrichTextEmbeddings_shouldPreserveTextAssetInputBehavior() throws Exception {
        Asset asset = Asset.builder().id("asset-1").kbId("kb-1").fileType("PDF").build();
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .chunkText("document body")
                .ocrText("ignored OCR")
                .build();
        List<Float> embedding = List.of(0.5f, 0.6f);
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed("document body", "text")).thenReturn(embedding);

        invokeEnrichEmbeddings(asset, List.of(chunk), "https://example.test/document.pdf");

        assertSame(embedding, chunk.getEmbedding());
        verify(embeddingPort).embed("document body", "text");
        verify(embeddingPort, never()).embed(any(), eq("image"));
    }

    private void invokeCleanup(IngestionTaskItem item) throws Exception {
        Method method = IngestionTaskProcessorImpl.class.getDeclaredMethod(
                "cleanupOverwrittenAsset", String.class, IngestionTaskItem.class, String.class);
        method.setAccessible(true);
        method.invoke(processor, "kb-1", item, "user-a");
    }

    private void invokeEnrichEmbeddings(Asset asset, List<Chunk> chunks, String imageUrl) throws Exception {
        Method method = IngestionTaskProcessorImpl.class.getDeclaredMethod(
                "enrichTextEmbeddings", Asset.class, List.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(processor, asset, chunks, imageUrl);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private Asset imageAsset() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("image.png")
                .fileType("IMAGE")
                .objectKey("images/image.png")
                .build();
    }

    private ParseResponse parsedResponse() {
        ParseResponse.Chunk chunk = new ParseResponse.Chunk(
                "chunk/0",
                "text",
                "recognized text",
                "recognized text",
                List.of(1),
                15,
                "source",
                List.of(),
                List.of());
        return new ParseResponse(
                "request-1",
                "docling",
                "json",
                "recognized text",
                "image",
                List.of(),
                List.of(chunk),
                List.of(),
                List.of());
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
