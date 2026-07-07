package com.anchr.core.search.application.impl;

import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentIndexManagerImplConcurrencyTest {

    private static final EmbeddingProfile TEST_PROFILE =
            new EmbeddingProfile(1L, "EMBEDDING", "test-model", 1024, "test-profile");
    private static final EmbeddingProfile TEXT_EMBEDDING_V4 =
            new EmbeddingProfile(2L, "EMBEDDING", "text-embedding-v4", 1024, "fingerprint-v4");
    private static final EmbeddingProfile TEXT_EMBEDDING_V3 =
            new EmbeddingProfile(3L, "EMBEDDING", "text-embedding-v3", 1024, "fingerprint-v3");
    private static final EmbeddingProfileProvider PROFILE_PROVIDER =
            () -> Optional.of(TEST_PROFILE);

    @Test
    void retryCreateShouldEnqueueOnlyOneTaskUnderConcurrency() throws Exception {
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(queuedTasks::add);
        int callerCount = 16;
        ExecutorService callers = Executors.newFixedThreadPool(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callerCount);
        AtomicInteger accepted = new AtomicInteger();

        try {
            for (int i = 0; i < callerCount; i++) {
                callers.execute(() -> {
                    try {
                        start.await();
                        if (manager.retryCreate()) {
                            accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }

        assertEquals(1, accepted.get());
        assertEquals(1, queuedTasks.size());
    }

    @Test
    void asyncCreateShouldNotEnqueueDuplicateTaskAfterClaim() {
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(queuedTasks::add);

        manager.asyncCreate();
        manager.asyncCreate();

        assertEquals(1, queuedTasks.size());
    }

    @Test
    void retryCreateShouldRollbackClaimWhenExecutorRejectsTask() {
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectOnce = task -> {
            if (submissions.getAndIncrement() == 0) {
                throw new RejectedExecutionException("rejected for test");
            }
            queuedTasks.add(task);
        };
        SegmentIndexManagerImpl manager = newManager(rejectOnce);

        assertFalse(manager.retryCreate());
        assertTrue(manager.retryCreate());
        assertEquals(1, queuedTasks.size());
    }

    @Test
    void confirmRebuildShouldEnqueueOnlyOneTaskUnderConcurrency() throws Exception {
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(queuedTasks::add);
        manager.markReadyFromStatus(SegmentIndexStatusDTO.builder()
                .indexExists(true)
                .readable(true)
                .writable(true)
                .actualDim(768)
                .actualModel("old-model")
                .build());
        String taskId = manager.prepareRebuild();
        int callerCount = 16;
        ExecutorService callers = Executors.newFixedThreadPool(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callerCount);
        AtomicInteger accepted = new AtomicInteger();

        try {
            for (int i = 0; i < callerCount; i++) {
                callers.execute(() -> {
                    try {
                        start.await();
                        if (manager.confirmRebuild(taskId)) {
                            accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }

        assertEquals(1, accepted.get());
        assertEquals(1, queuedTasks.size());
        SegmentIndexStatusDTO rebuildingStatus = manager.status();
        assertEquals(SegmentIndexStatus.REBUILDING, rebuildingStatus.getStatus());
        assertTrue(rebuildingStatus.isReadable());
        assertFalse(rebuildingStatus.isWritable());
        assertEquals(0, rebuildingStatus.getRebuildProgress().getMigrated());
        assertEquals(0, rebuildingStatus.getRebuildProgress().getTotal());
        assertEquals("PREPARING", rebuildingStatus.getRebuildProgress().getPhase());
    }

    @Test
    void statusShouldRestoreWritableWhenExistingAliasIsDetectedOnStartup() {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        SegmentIndexAliasManager aliasManager = new SegmentIndexAliasManager(null, config) {
            @Override
            public AliasTopology inspect() {
                return new AliasTopology(
                        true,
                        true,
                        true,
                        true,
                        true,
                        "kb_segment_20260101000000",
                        "kb_segment_20260101000000",
                        null);
            }
        };
        SegmentIndexManagerImpl manager = new SegmentIndexManagerImpl(
                null,
                config,
                PROFILE_PROVIDER,
                null,
                null,
                Runnable::run,
                new SegmentIndexWriteBarrier(),
                aliasManager);

        SegmentIndexStatusDTO status = manager.status();

        assertEquals(SegmentIndexStatus.READY, status.getStatus());
        assertTrue(status.isIndexExists());
        assertTrue(status.isReadable());
        assertTrue(status.isWritable());
    }

    @Test
    void prepareRebuildShouldRejectInvalidAliasTopology() {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        SegmentIndexAliasManager aliasManager = new SegmentIndexAliasManager(null, config) {
            @Override
            public AliasTopology inspect() {
                return AliasTopology.unavailable("broken topology");
            }
        };
        SegmentIndexManagerImpl manager = new SegmentIndexManagerImpl(
                null,
                config,
                PROFILE_PROVIDER,
                null,
                null,
                Runnable::run,
                new SegmentIndexWriteBarrier(),
                aliasManager);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                manager::prepareRebuild);
        assertTrue(error.getMessage().contains("索引 alias 不合法"));
    }

    @Test
    void prepareRebuildShouldClearPendingTaskWhenActiveProfileMatchesActualIndex() {
        AtomicReference<EmbeddingProfile> activeProfile =
                new AtomicReference<>(TEXT_EMBEDDING_V3);
        SegmentIndexManagerImpl manager = newManager(
                Runnable::run,
                () -> Optional.of(activeProfile.get()));
        manager.markReadyFromStatus(SegmentIndexStatusDTO.builder()
                .indexExists(true)
                .readable(true)
                .writable(true)
                .actualDim(TEXT_EMBEDDING_V4.dimension())
                .actualModel(TEXT_EMBEDDING_V4.modelName())
                .actualProfileFingerprint(TEXT_EMBEDDING_V4.fingerprint())
                .build());

        String oldTaskId = manager.prepareRebuild();
        assertEquals(oldTaskId, manager.status().getPendingRebuild().getTaskId());

        activeProfile.set(TEXT_EMBEDDING_V4);

        assertNull(manager.prepareRebuild());
        assertNull(manager.status().getPendingRebuild());
    }

    @Test
    void statusShouldClearPendingTaskWhenActiveProfileMatchesActualIndex() {
        AtomicReference<EmbeddingProfile> activeProfile =
                new AtomicReference<>(TEXT_EMBEDDING_V3);
        SegmentIndexManagerImpl manager = newManager(
                Runnable::run,
                () -> Optional.of(activeProfile.get()));
        manager.markReadyFromStatus(SegmentIndexStatusDTO.builder()
                .indexExists(true)
                .readable(true)
                .writable(true)
                .actualDim(TEXT_EMBEDDING_V4.dimension())
                .actualModel(TEXT_EMBEDDING_V4.modelName())
                .actualProfileFingerprint(TEXT_EMBEDDING_V4.fingerprint())
                .build());

        String oldTaskId = manager.prepareRebuild();
        assertEquals(oldTaskId, manager.status().getPendingRebuild().getTaskId());

        activeProfile.set(TEXT_EMBEDDING_V4);

        assertNull(manager.status().getPendingRebuild());
    }

    @Test
    void confirmRebuildShouldRejectAndClearStalePendingTask() {
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        AtomicReference<EmbeddingProfile> activeProfile =
                new AtomicReference<>(TEXT_EMBEDDING_V3);
        SegmentIndexManagerImpl manager = newManager(
                queuedTasks::add,
                () -> Optional.of(activeProfile.get()));
        manager.markReadyFromStatus(SegmentIndexStatusDTO.builder()
                .indexExists(true)
                .readable(true)
                .writable(true)
                .actualDim(TEXT_EMBEDDING_V4.dimension())
                .actualModel(TEXT_EMBEDDING_V4.modelName())
                .actualProfileFingerprint(TEXT_EMBEDDING_V4.fingerprint())
                .build());
        String oldTaskId = manager.prepareRebuild();

        activeProfile.set(TEXT_EMBEDDING_V4);

        assertFalse(manager.confirmRebuild(oldTaskId));
        assertEquals(0, queuedTasks.size());
        assertNull(manager.status().getPendingRebuild());
    }

    private SegmentIndexManagerImpl newManager(Executor executor) {
        return newManager(executor, PROFILE_PROVIDER);
    }

    private SegmentIndexManagerImpl newManager(
            Executor executor,
            EmbeddingProfileProvider profileProvider
    ) {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        SegmentIndexAliasManager aliasManager = new SegmentIndexAliasManager(null, config) {
            @Override
            public AliasTopology inspect() {
                return new AliasTopology(
                        true,
                        true,
                        true,
                        true,
                        true,
                        "kb_segment_20260101000000",
                        "kb_segment_20260101000000",
                        null);
            }
        };
        return new SegmentIndexManagerImpl(
                null,
                config,
                profileProvider,
                null,
                null,
                executor,
                new SegmentIndexWriteBarrier(),
                aliasManager);
    }
}
