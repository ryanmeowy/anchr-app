package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactStore;
import com.anchr.core.ingestion.application.artifact.IngestionStoredArtifact;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.integration.ai.client.AiClient;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskProcessorImplTest {

    private static final String PARSE_ARTIFACT_SHA256 = "a".repeat(64);

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
    private StorageTokenIssuer storageTokenIssuer;
    @Mock
    private IngestionIndexFinalizer ingestionIndexFinalizer;
    @Mock
    private IngestionStageTransactionCoordinator transactionCoordinator;
    @Mock
    private IngestionObjectStoragePort objectStoragePort;
    @Mock
    private StorageConfigRepository storageConfigRepository;
    @Mock
    private DoclingChunkMapper doclingChunkMapper;
    @Mock
    private DoclingClient doclingClient;
    @Mock
    private IngestionArtifactStore artifactStore;
    @Mock
    private IdGen idGen;

    private ObjectMapper objectMapper;
    private IngestionTaskProcessorImpl processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        lenient().when(transactionCoordinator.ensureTargetIndexGeneration(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(idGen.nextIdStr()).thenReturn("visual-segment-1");
        processor = processor(Runnable::run);
    }

    @Test
    void submit_shouldLeaveItemInDatabaseWhenExecutorRejectsDispatch() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };
        IngestionTaskProcessorImpl overloaded = processor(rejecting);
        when(ingestionTaskRepository.listClaimableItemIds("task-1", 32))
                .thenReturn(List.of("item-1"));

        overloaded.submit("kb-1", "task-1", "user-a");

        verify(ingestionTaskRepository, never()).claimOne(any(), anyLong());
    }

    @Test
    void pollDueItems_shouldRedispatchExpiredLeaseWhilePriorWorkerIsStillBlocked()
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch priorWorkerBlocked = new CountDownLatch(1);
        CountDownLatch releasePriorWorker = new CountDownLatch(1);
        CountDownLatch replacementWorkerReachedProvider = new CountDownLatch(1);
        try {
            IngestionTaskProcessorImpl asyncProcessor = processor(workers);
            IngestionTaskItem priorClaim = claimed(IngestionExecutionStage.PARSE_WAIT)
                    .toBuilder()
                    .claimVersion(1)
                    .leaseToken("lease-old")
                    .doclingJobId("job-old")
                    .build();
            IngestionTaskItem replacementClaim = priorClaim.toBuilder()
                    .claimVersion(2)
                    .stageRetryCount(1)
                    .leaseToken("lease-new")
                    .doclingJobId("job-new")
                    .build();
            Asset asset = pdfAsset("objects/document.pdf", null);

            // The second scan models the database exposing the item after lease expiry.
            when(ingestionTaskRepository.listClaimableItemIds(32))
                    .thenReturn(List.of("item-1"), List.of("item-1"));
            when(ingestionTaskRepository.claimOne(eq("item-1"), anyLong()))
                    .thenReturn(Optional.of(priorClaim), Optional.of(replacementClaim));
            when(assetRepository.findActiveById("kb-1", "asset-1"))
                    .thenReturn(Optional.of(asset));
            when(doclingClient.getJob(any(), eq("task-1:item-1:1")))
                    .thenAnswer(invocation -> {
                        String jobId = invocation.getArgument(0);
                        if ("job-old".equals(jobId)) {
                            priorWorkerBlocked.countDown();
                            if (!releasePriorWorker.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("prior worker was not released");
                            }
                        } else if ("job-new".equals(jobId)) {
                            replacementWorkerReachedProvider.countDown();
                        }
                        return new DoclingClient.DoclingJob(
                                jobId, "task-1:item-1:1", "running", null, null);
                    });
            when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

            asyncProcessor.pollDueItems();
            assertThat(priorWorkerBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            asyncProcessor.pollDueItems();

            assertThat(replacementWorkerReachedProvider.await(5, TimeUnit.SECONDS)).isTrue();
            verify(ingestionTaskRepository, times(2))
                    .claimOne(eq("item-1"), anyLong());
        } finally {
            releasePriorWorker.countDown();
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            assertThat(workers.isTerminated()).isTrue();
        }
    }

    @Test
    void parseSubmit_shouldPersistStableContextAndDeferOneQueuedJob() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_SUBMIT);
        Asset asset = pdfAsset(null, "https://source.example.test/document.pdf");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(ingestionTaskRepository.updateClaimContext(any())).thenReturn(true);
        when(transactionCoordinator.updateAssetStatusForCurrentClaim(
                any(IngestionTaskItem.class), eq(asset), any(), any())).thenReturn(true);
        when(doclingClient.submitJob(any())).thenReturn(new DoclingClient.DoclingJob(
                "job-1", "task-1:item-1:1", "queued", null, null));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        ArgumentCaptor<IngestionClaimContext> context =
                ArgumentCaptor.forClass(IngestionClaimContext.class);
        verify(ingestionTaskRepository).updateClaimContext(context.capture());
        assertThat(context.getValue().getDoclingRequestId())
                .isEqualTo("task-1:item-1:1");
        assertThat(context.getValue().getParseRequestSnapshot())
                .doesNotContain("source.example.test");

        ArgumentCaptor<ParseRequest> request = ArgumentCaptor.forClass(ParseRequest.class);
        verify(doclingClient).submitJob(request.capture());
        assertThat(request.getValue().sourceUrl())
                .isEqualTo("https://source.example.test/document.pdf");
        assertThat(request.getValue().contractVersion()).isEqualTo(2);
        assertThat(request.getValue().options().includeEmbeddedImages()).isFalse();
        assertThat(request.getValue().oss()).isNull();
        verifyNoInteractions(objectStoragePort);

        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_WAIT);
        assertThat(transition.getDoclingJobId()).isEqualTo("job-1");
        assertThat(transition.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(transition.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(transition.getProgress()).isEqualTo(20);
    }

    @Test
    void parseSubmit_transportTimeoutAfterContextFillMustNotClearStableSnapshot() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_SUBMIT).toBuilder()
                .doclingRequestId(null)
                .sourceRevision(null)
                .parseRequestSnapshot(null)
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(ingestionTaskRepository.updateClaimContext(any())).thenReturn(true);
        when(transactionCoordinator.updateAssetStatusForCurrentClaim(
                any(IngestionTaskItem.class), eq(asset), any(), any())).thenReturn(true);
        when(objectStoragePort.buildDownloadUrl("objects/document.pdf"))
                .thenReturn("https://signed.example.test/document.pdf?expires=1");
        DoclingClient.DoclingClientException timeout =
                org.mockito.Mockito.mock(DoclingClient.DoclingClientException.class);
        when(timeout.kind()).thenReturn(DoclingClient.FailureKind.TRANSIENT);
        when(timeout.retryAfter()).thenReturn(Duration.ofSeconds(3));
        when(timeout.getMessage()).thenReturn("response lost");
        when(doclingClient.submitJob(any())).thenThrow(timeout);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(transition.getDoclingRequestId())
                .isEqualTo("task-1:item-1:1");
        assertThat(transition.getSourceRevision()).startsWith("v1:");
        assertThat(transition.getParseRequestSnapshot())
                .isNotBlank()
                .doesNotContain("signed.example.test");
        assertThat(transition.getNextStageRetryCount()).isEqualTo(1);
    }

    @Test
    void parseSubmit_ambiguousContextWriteMustWaitForLeaseRecovery() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_SUBMIT).toBuilder()
                .doclingRequestId(null)
                .sourceRevision(null)
                .parseRequestSnapshot(null)
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        when(ingestionTaskRepository.updateClaimContext(any()))
                .thenThrow(new RuntimeException("connection lost after commit"));

        processor.processClaim(item);

        verify(ingestionTaskRepository, never()).transitionClaim(any());
        verifyNoInteractions(doclingClient);
        verify(transactionCoordinator).ensureTargetIndexGeneration(item);
        verifyNoMoreInteractions(transactionCoordinator);
    }

    @Test
    void parseWait_shouldPollOnceWithoutIncrementingRetryCount() {
        LocalDateTime started = LocalDateTime.now().minusMinutes(2);
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_WAIT).toBuilder()
                .stageRetryCount(2)
                .stageStartedAt(started)
                .doclingJobId("job-1")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1", "task-1:item-1:1", "running", null, null));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_WAIT);
        assertThat(transition.getNextStageRetryCount()).isEqualTo(2);
        assertThat(transition.getNextStageStartedAt()).isEqualTo(started);
        assertThat(transition.getNextActionAt()).isAfter(LocalDateTime.now());
        verify(doclingClient, never()).ackJob(any());
    }

    @Test
    void parseWait_shouldResubmitLostJobWithSameParseIdentity() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_WAIT).toBuilder()
                .doclingJobId("lost-job")
                .parseRequestSnapshot(snapshotJson())
                .sourceRevision("v1:revision")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        DoclingClient.DoclingClientException notFound =
                org.mockito.Mockito.mock(DoclingClient.DoclingClientException.class);
        when(notFound.kind()).thenReturn(DoclingClient.FailureKind.NOT_FOUND);
        when(doclingClient.getJob("lost-job", "task-1:item-1:1"))
                .thenThrow(notFound);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(transition.getParseAttempt()).isEqualTo(1);
        assertThat(transition.getDoclingRequestId())
                .isEqualTo("task-1:item-1:1");
        assertThat(transition.getSourceRevision()).isEqualTo("v1:revision");
        assertThat(transition.getParseRequestSnapshot()).isEqualTo(snapshotJson());
        assertThat(transition.getDoclingJobId()).isNull();
        assertThat(transition.getNextStageRetryCount()).isEqualTo(1);
    }

    @Test
    void parseWait_shouldAckRetryableFailedJobBeforeClearingIt() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_WAIT).toBuilder()
                .doclingJobId("job-1")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1",
                        "task-1:item-1:1",
                        "failed",
                        null,
                        new DoclingClient.DoclingJobError(
                                "QUEUE_TIMEOUT", "queue expired")));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        InOrder order = inOrder(doclingClient, ingestionTaskRepository);
        order.verify(doclingClient).ackJob("job-1");
        order.verify(ingestionTaskRepository).transitionClaim(any());
        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(transition.getDoclingJobId()).isNull();
    }

    @Test
    void parseWait_ackFailureShouldRetryWithoutChangingParseIdentity() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_WAIT).toBuilder()
                .doclingJobId("job-1")
                .sourceRevision("v1:revision")
                .parseRequestSnapshot(snapshotJson())
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1",
                        "task-1:item-1:1",
                        "failed",
                        null,
                        new DoclingClient.DoclingJobError(
                                "QUEUE_TIMEOUT", "queue expired")));
        DoclingClient.DoclingClientException ackFailure =
                org.mockito.Mockito.mock(DoclingClient.DoclingClientException.class);
        when(ackFailure.kind()).thenReturn(DoclingClient.FailureKind.TRANSIENT);
        when(ackFailure.retryAfter()).thenReturn(Duration.ofSeconds(2));
        when(ackFailure.getMessage()).thenReturn("ACK response lost");
        org.mockito.Mockito.doThrow(ackFailure).when(doclingClient).ackJob("job-1");
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_WAIT);
        assertThat(transition.getNextStageRetryCount()).isEqualTo(1);
        assertThat(transition.getParseAttempt()).isEqualTo(1);
        assertThat(transition.getDoclingRequestId()).isEqualTo("task-1:item-1:1");
        assertThat(transition.getDoclingJobId()).isEqualTo("job-1");
        assertThat(transition.getSourceRevision()).isEqualTo("v1:revision");
        assertThat(transition.getParseRequestSnapshot()).isEqualTo(snapshotJson());
    }

    @Test
    void parsePersist_shouldWriteReferenceBeforeAck() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_PERSIST).toBuilder()
                .doclingJobId("job-1")
                .sourceRevision("v1:revision")
                .build();
        ParseResponse response = parsedResponse("task-1:item-1:1");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        IngestionStoredArtifact storedArtifact = new IngestionStoredArtifact(
                "ingestion/task-1/item-1/parse-result.gz",
                1,
                PARSE_ARTIFACT_SHA256);
        when(artifactStore.writeParseArtifact(item, "job-1", response))
                .thenReturn(storedArtifact);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        InOrder order = inOrder(artifactStore, ingestionTaskRepository, doclingClient);
        order.verify(artifactStore).writeParseArtifact(item, "job-1", response);
        order.verify(ingestionTaskRepository).transitionClaim(any());
        order.verify(doclingClient).ackJob("job-1");
        IngestionClaimTransition transition = captureRepositoryTransition();
        assertThat(transition.getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.EMBED);
        assertThat(transition.getParseResultObjectKey())
                .isEqualTo(storedArtifact.objectKey());
        assertThat(transition.getParseResultSha256())
                .isEqualTo(storedArtifact.sha256());
    }

    @Test
    void parsePersist_shouldDeferAckUntilAnOuterTransactionCommits() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_PERSIST).toBuilder()
                .doclingJobId("job-1")
                .sourceRevision("v1:revision")
                .build();
        ParseResponse response = parsedResponse("task-1:item-1:1");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(artifactStore.writeParseArtifact(item, "job-1", response))
                .thenReturn(new IngestionStoredArtifact(
                        "parse-result.gz", 1, PARSE_ARTIFACT_SHA256));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            processor.processClaim(item);
            verify(doclingClient, never()).ackJob(any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(doclingClient).ackJob("job-1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void parsePersist_shouldNotAckWhenArtifactReferenceLosesFence() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_PERSIST).toBuilder()
                .doclingJobId("job-1")
                .sourceRevision("v1:revision")
                .build();
        ParseResponse response = parsedResponse("task-1:item-1:1");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(pdfAsset("objects/document.pdf", null)));
        when(doclingClient.getJob("job-1", "task-1:item-1:1"))
                .thenReturn(new DoclingClient.DoclingJob(
                        "job-1", "task-1:item-1:1", "succeeded", response, null));
        when(artifactStore.writeParseArtifact(item, "job-1", response))
                .thenReturn(new IngestionStoredArtifact(
                        "parse-result.gz", 1, PARSE_ARTIFACT_SHA256));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(false);

        processor.processClaim(item);

        verify(doclingClient, never()).ackJob(any());
    }

    @Test
    void embedWithMultimodalModel_shouldUseTextInputForOrdinaryDocument() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body text")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed("body text", "text"))
                .thenReturn(List.of(0.1f, 0.2f));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionAndUpdateAssetStatus(
                any(), eq(asset), any(), any())).thenReturn(true);
        when(ingestionIndexFinalizer.finalizeIndex(
                any(IngestionTaskItem.class), eq(asset), any())).thenReturn(true);

        processor.processClaim(item);

        verify(ingestionTaskRepository, times(3)).renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong());
        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(transactionCoordinator).transitionAndUpdateAssetStatus(
                transition.capture(), eq(asset), eq("SUCCESS"), eq("RUNNING"));
        assertThat(transition.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.INDEX);
        assertThat(transition.getValue().isRetainLease()).isTrue();
        verify(embeddingPort, times(1)).embed("body text", "text");
        ArgumentCaptor<IngestionTaskItem> indexClaim =
                ArgumentCaptor.forClass(IngestionTaskItem.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Segment>> segments =
                ArgumentCaptor.forClass(List.class);
        verify(ingestionIndexFinalizer).finalizeIndex(
                indexClaim.capture(), eq(asset), segments.capture());
        assertThat(indexClaim.getValue().getExecutionStage())
                .isEqualTo(IngestionExecutionStage.INDEX);
        assertThat(indexClaim.getValue().getClaimVersion()).isEqualTo(item.getClaimVersion());
        assertThat(indexClaim.getValue().getLeaseToken()).isEqualTo(item.getLeaseToken());
        assertThat(segments.getValue()).singleElement().satisfies(segment -> {
            assertThat(segment.getSegmentType()).isEqualTo(SegmentType.TEXT_CHUNK);
            assertThat(segment.getEmbedding()).containsExactly(0.1f, 0.2f);
        });
    }

    @Test
    void embed_shouldCompleteNormallyWhenImageHasNoExtractedText() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = new ParseResponse(
                "task-1:item-1:1",
                "docling",
                "chunks",
                "",
                "image",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of());
        when(embeddingPort.isMulti()).thenReturn(false);
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionAndUpdateAssetStatus(
                any(), eq(asset), any(), any())).thenReturn(true);
        when(ingestionIndexFinalizer.finalizeIndex(
                any(IngestionTaskItem.class), eq(asset), eq(List.of())))
                .thenReturn(true);

        processor.processClaim(item);

        verify(embeddingPort, never()).embed(any(), any());
        verify(transactionCoordinator).transitionAndUpdateAssetStatus(
                any(), eq(asset), eq("SUCCESS"), eq("RUNNING"));
        verify(ingestionIndexFinalizer).finalizeIndex(
                any(IngestionTaskItem.class), eq(asset), eq(List.of()));
        verify(transactionCoordinator, never()).transitionFailed(
                any(), eq(asset), any(), any(), anyInt(), anyInt());
        verify(knowledgeBaseRepository).refreshDocumentStats("kb-1", "user-a", true);
    }

    @Test
    void embed_shouldRejectEmptyContentForNonImageDocument() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = pdfAsset("objects/image-only.pdf", null);
        ParseResponse parsed = new ParseResponse(
                "task-1:item-1:1",
                "docling",
                "chunks",
                "",
                "pdf",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(transactionCoordinator.transitionFailed(
                any(), eq(asset), any(), any(), anyInt(), anyInt()))
                .thenReturn(true);

        processor.processClaim(item);

        verifyNoInteractions(doclingChunkMapper);
        verifyNoInteractions(embeddingPort);
        verifyNoInteractions(ingestionIndexFinalizer);
        ArgumentCaptor<IngestionClaimTransition> failure =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(transactionCoordinator).transitionFailed(
                failure.capture(), eq(asset), eq("FAILED"), eq("FAILED"),
                eq(0), eq(0));
        assertThat(failure.getValue().getErrorCode())
                .isEqualTo("TEXT_PARSE_FAILED");
        assertThat(failure.getValue().getErrorMessage())
                .isEqualTo("Docling returned empty chunks.");
    }

    @Test
    void embedImageWithTextModel_shouldUseOcrAndAllowBlankOcrChunks() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk blankOcr = imageChunk("segment-1", null);
        Chunk textOcr = imageChunk("segment-2", "detected text");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(blankOcr, textOcr));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("detected text", "text"))
                .thenReturn(List.of(0.3f, 0.4f));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionAndUpdateAssetStatus(
                any(), eq(asset), any(), any())).thenReturn(true);

        processor.processClaim(item);

        List<Segment> segments = capturedSegments(asset);
        assertThat(segments).hasSize(2);
        assertThat(segments).allMatch(segment ->
                segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK);
        assertThat(segments).filteredOn(segment ->
                        "segment-1".equals(segment.getSegmentId()))
                .singleElement()
                .satisfies(segment -> assertThat(segment.getEmbedding()).isNull());
        assertThat(segments).filteredOn(segment ->
                        "segment-2".equals(segment.getSegmentId()))
                .singleElement()
                .satisfies(segment -> assertThat(segment.getEmbedding())
                        .containsExactly(0.3f, 0.4f));
        verify(embeddingPort, times(1)).embed("detected text", "text");
        verify(objectStoragePort, never()).buildDownloadUrl(any());
        verify(transactionCoordinator).transitionAndUpdateAssetStatus(
                any(), eq(asset), eq("SUCCESS"), eq("RUNNING"));
    }

    @Test
    void embedImageWithTextModel_shouldAdvanceWhenAllOcrIsBlank() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk blankOcr = imageChunk("segment-1", " ");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(blankOcr));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionAndUpdateAssetStatus(
                any(), eq(asset), any(), any())).thenReturn(true);

        processor.processClaim(item);

        assertThat(capturedSegments(asset)).singleElement().satisfies(segment -> {
            assertThat(segment.getSegmentType())
                    .isEqualTo(SegmentType.IMAGE_OCR_BLOCK);
            assertThat(segment.getEmbedding()).isNull();
        });
        verify(embeddingPort, never()).embed(any(), any());
        verify(objectStoragePort, never()).buildDownloadUrl(any());
        verify(transactionCoordinator).transitionAndUpdateAssetStatus(
                any(), eq(asset), eq("SUCCESS"), eq("RUNNING"));
    }

    @Test
    void embedImageWithMultimodalModel_shouldCreateOneAssetVisualSegment() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        BboxInfo firstBox = BboxInfo.builder()
                .pageNo(1)
                .bbox(BboxInfo.Bbox.builder()
                        .l(1).t(2).r(3).b(4).build())
                .build();
        Chunk first = imageChunk("segment-1", " ");
        first.setPageNo(1);
        first.setChunkOrder(4);
        first.setBboxInfos(List.of(firstBox));
        Chunk second = imageChunk("segment-2", null);
        second.setPageNo(2);
        second.setChunkOrder(8);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(first, second));
        when(objectStoragePort.buildImageEmbeddingUrl("images/image.png"))
                .thenReturn("https://signed.example.test/compressed-image.jpg");
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed(
                "https://signed.example.test/compressed-image.jpg", "image"))
                .thenReturn(List.of(0.8f, 0.9f));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionAndUpdateAssetStatus(
                any(), eq(asset), any(), any())).thenReturn(true);

        processor.processClaim(item);

        List<Segment> segments = capturedSegments(asset);
        assertThat(segments).hasSize(3);
        assertThat(segments).filteredOn(segment ->
                        segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK)
                .hasSize(2)
                .allSatisfy(segment -> assertThat(segment.getEmbedding()).isNull());
        assertThat(segments).filteredOn(segment ->
                        segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK)
                .extracting(
                        Segment::getSegmentId,
                        Segment::getOcrText,
                        Segment::getPageNo,
                        Segment::getChunkOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "segment-1", " ", 1, 4),
                        org.assertj.core.groups.Tuple.tuple(
                                "segment-2", null, 2, 8));
        assertThat(segments).filteredOn(segment ->
                        "segment-1".equals(segment.getSegmentId()))
                .singleElement()
                .satisfies(segment -> assertThat(segment.getBbox())
                        .containsExactly(firstBox));
        assertThat(segments).filteredOn(segment ->
                        segment.getSegmentType() == SegmentType.IMAGE_VISUAL)
                .singleElement()
                .satisfies(segment -> {
                    assertThat(segment.getSegmentId()).isEqualTo("visual-segment-1");
                    assertThat(segment.getEmbedding()).containsExactly(0.8f, 0.9f);
                    assertThat(segment.getOcrText()).isNull();
                    assertThat(segment.getChunkOrder()).isZero();
                    assertThat(segment.getSourceRef()).isEqualTo("images/image.png");
                });
        verify(embeddingPort, times(1))
                .embed("https://signed.example.test/compressed-image.jpg", "image");
        verify(objectStoragePort, never()).buildDownloadUrl("images/image.png");
    }

    @Test
    void embedImageWithMultimodalModel_shouldFailWhenImageEmbeddingIsEmpty() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED)
                .toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = imageAsset();
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(imageChunk("segment-1", null)));
        when(objectStoragePort.buildImageEmbeddingUrl("images/image.png"))
                .thenReturn("https://signed.example.test/compressed-image.jpg");
        when(embeddingPort.isMulti()).thenReturn(true);
        when(embeddingPort.embed(
                "https://signed.example.test/compressed-image.jpg", "image"))
                .thenReturn(List.of());
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionFailed(
                any(), eq(asset), any(), any(), anyInt(), anyInt()))
                .thenReturn(true);

        processor.processClaim(item);

        ArgumentCaptor<IngestionClaimTransition> failed =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(transactionCoordinator).transitionFailed(
                failed.capture(), eq(asset), eq("FAILED"), eq("FAILED"),
                eq(0), eq(0));
        assertThat(failed.getValue().getErrorCode())
                .isEqualTo("EMBEDDING_RESULT_EMPTY");
        verifyNoInteractions(ingestionIndexFinalizer);
    }

    @Test
    void embedRateLimit_shouldTreatConfiguredMaxAttemptsAsTotalProviderCalls() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .stageRetryCount(4)
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body text")
                .build();
        ReflectionTestUtils.setField(processor, "stageMaxRetries", 5);
        ReflectionTestUtils.setField(processor, "embeddingRateLimitMaxAttempts", 5);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("body text", "text"))
                .thenThrow(new AiClient.OpenAiException(429, "quota exhausted"));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(transactionCoordinator.transitionFailed(
                any(), eq(asset), any(), any(), anyInt(), anyInt())).thenReturn(true);

        processor.processClaim(item);

        verify(embeddingPort, times(1)).embed("body text", "text");
        ArgumentCaptor<IngestionClaimTransition> failed =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(transactionCoordinator).transitionFailed(
                failed.capture(), eq(asset), eq("FAILED"), eq("FAILED"), eq(0), eq(0));
        assertThat(failed.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.FAILED);
        assertThat(failed.getValue().getErrorCode()).isEqualTo("EMBEDDING_FAILED");
        verify(ingestionTaskRepository, never()).transitionClaim(any());
    }

    @Test
    void embedInterrupt_shouldLeaveClaimForLeaseRecovery() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.EMBED).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .sourceRevision("v1:revision")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body text")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.EMBED),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(
                processor, "nextEmbeddingCallAt", System.currentTimeMillis() + 10_000L);

        Thread.currentThread().interrupt();
        try {
            processor.processClaim(item);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(transactionCoordinator).ensureTargetIndexGeneration(item);
        verifyNoMoreInteractions(transactionCoordinator);
        verify(embeddingPort, never()).embed(any(), any());
    }

    @Test
    void indexRecovery_shouldRebuildEmbeddingsFromParseArtifact() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.INDEX).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("body", "text")).thenReturn(List.of(0.1f));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.INDEX),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(ingestionIndexFinalizer.finalizeIndex(
                eq(item), eq(asset), any())).thenReturn(true);

        processor.processClaim(item);

        assertThat(capturedSegments(asset)).singleElement().satisfies(segment ->
                assertThat(segment.getEmbedding()).containsExactly(0.1f));
        verify(artifactStore).readParseResult(item);
        verify(embeddingPort).embed("body", "text");
        verifyNoInteractions(doclingClient);
        verify(knowledgeBaseRepository).refreshDocumentStats("kb-1", "user-a", true);
    }

    @Test
    void indexRecoveryRateLimit_shouldRetryIndexWithoutPersistingVectors() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.INDEX).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("body", "text"))
                .thenThrow(new AiClient.OpenAiException(429, "quota exhausted"));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.INDEX),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        processor.processClaim(item);

        ArgumentCaptor<IngestionClaimTransition> retry =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(ingestionTaskRepository).transitionClaim(retry.capture());
        assertThat(retry.getValue().getExpectedExecutionStage())
                .isEqualTo(IngestionExecutionStage.INDEX);
        assertThat(retry.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.INDEX);
        assertThat(retry.getValue().getNextStageRetryCount()).isEqualTo(1);
        assertThat(retry.getValue().getNextActionAt()).isAfter(LocalDateTime.now());
        verifyNoInteractions(ingestionIndexFinalizer);
    }

    @Test
    void index_shouldDelegateOverwrittenAssetCleanupToFinalizer() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.INDEX).toBuilder()
                .parseResultObjectKey("parse-result.gz")
                .dedupeResult(DedupeResult.OVERWRITTEN)
                .duplicateAssetId("asset-old")
                .build();
        Asset asset = pdfAsset("objects/document.pdf", null);
        ParseResponse parsed = parsedResponse("task-1:item-1:1");
        Chunk chunk = Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .chunkText("body")
                .build();
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(artifactStore.readParseResult(item)).thenReturn(parsed);
        when(doclingChunkMapper.toTextChunks(asset, parsed, 1L))
                .thenReturn(List.of(chunk));
        when(embeddingPort.isMulti()).thenReturn(false);
        when(embeddingPort.embed("body", "text")).thenReturn(List.of(0.1f));
        when(ingestionTaskRepository.renewClaim(
                eq("item-1"), eq(1L), eq(IngestionExecutionStage.INDEX),
                eq(2L), eq("lease-1"), anyLong())).thenReturn(true);
        when(ingestionIndexFinalizer.finalizeIndex(
                eq(item), eq(asset), any())).thenReturn(true);
        processor.processClaim(item);

        verify(ingestionIndexFinalizer).finalizeIndex(eq(item), eq(asset), any());
        verify(assetRepository, never()).markDeleted(
                eq("kb-1"), eq("asset-old"), eq("user-a"), any(LocalDateTime.class));
    }

    @Test
    void missingAsset_shouldProduceFencedTerminalFailure() {
        IngestionTaskItem item = claimed(IngestionExecutionStage.PARSE_SUBMIT);
        when(assetRepository.findActiveById("kb-1", "asset-1"))
                .thenReturn(Optional.empty());
        when(transactionCoordinator.transitionFailed(
                any(), eq(null), any(), any(), anyInt(), anyInt()))
                .thenReturn(true);

        processor.processClaim(item);

        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(transactionCoordinator).transitionFailed(
                transition.capture(), eq(null), eq("FAILED"), eq("FAILED"), eq(0), eq(0));
        assertThat(transition.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.FAILED);
        assertThat(transition.getValue().getErrorCode())
                .isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private List<Segment> capturedSegments(Asset asset) {
        ArgumentCaptor<List<Segment>> segments =
                ArgumentCaptor.forClass(List.class);
        verify(ingestionIndexFinalizer).finalizeIndex(
                any(IngestionTaskItem.class), eq(asset), segments.capture());
        return segments.getValue();
    }

    private IngestionTaskProcessorImpl processor(Executor executor) {
        return new IngestionTaskProcessorImpl(
                executor,
                ingestionTaskRepository,
                assetRepository,
                knowledgeBaseRepository,
                embeddingPort,
                aesUtil,
                storageTokenIssuer,
                ingestionIndexFinalizer,
                transactionCoordinator,
                objectStoragePort,
                storageConfigRepository,
                doclingChunkMapper,
                doclingClient,
                artifactStore,
                objectMapper,
                idGen);
    }

    private IngestionTaskItem claimed(IngestionExecutionStage stage) {
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.running(stage, 0);
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .taskCreatedBy("user-a")
                .assetId("asset-1")
                .targetIndexGeneration(1L)
                .fileName("document.pdf")
                .parseAttempt(1)
                .doclingRequestId("task-1:item-1:1")
                .sourceRevision("v1:revision")
                .executionStage(stage)
                .executionEpoch(1L)
                .claimVersion(2)
                .stageRetryCount(0)
                .stageStartedAt(LocalDateTime.now().minusSeconds(10))
                .leaseToken("lease-1")
                .leaseUntil(LocalDateTime.now().plusMinutes(5))
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .dedupeResult(DedupeResult.NEW)
                .build();
    }

    private Asset pdfAsset(String objectKey, String sourceUrl) {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .fileType("PDF")
                .objectKey(objectKey)
                .sourceUrl(sourceUrl)
                .segmentCount(0)
                .indexedSegmentCount(0)
                .build();
    }

    private Asset imageAsset() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("image.png")
                .fileType("IMAGE")
                .objectKey("images/image.png")
                .segmentCount(0)
                .indexedSegmentCount(0)
                .build();
    }

    private Chunk imageChunk(String segmentId, String ocrText) {
        return Chunk.builder()
                .segmentId(segmentId)
                .kbId("kb-1")
                .assetId("asset-1")
                .ocrText(ocrText)
                .build();
    }

    private ParseResponse parsedResponse(String requestId) {
        ParseResponse.Chunk chunk = new ParseResponse.Chunk(
                "chunk/0",
                "text",
                "body",
                "body",
                List.of(1),
                4,
                "source",
                List.of(),
                List.of());
        return new ParseResponse(
                requestId,
                "docling",
                "json",
                "body",
                "pdf",
                List.of(),
                List.of(chunk),
                List.of(),
                List.of());
    }

    private String snapshotJson() {
        try {
            return objectMapper.writeValueAsString(
                    IngestionParseRequestSnapshot.capture(
                            pdfAsset("objects/document.pdf", null), false, null));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private IngestionClaimTransition captureRepositoryTransition() {
        ArgumentCaptor<IngestionClaimTransition> captor =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(ingestionTaskRepository).transitionClaim(captor.capture());
        return captor.getValue();
    }
}
