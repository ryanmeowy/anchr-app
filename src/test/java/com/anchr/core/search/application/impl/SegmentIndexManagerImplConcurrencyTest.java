package com.anchr.core.search.application.impl;

import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentIndexManagerImplConcurrencyTest {

    private static final EmbeddingProfile TEST_PROFILE =
            new EmbeddingProfile(1L, "EMBEDDING", "test-model", 1024, "test-profile");
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
    }

    private SegmentIndexManagerImpl newManager(Executor executor) {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        return new SegmentIndexManagerImpl(
                null,
                config,
                PROFILE_PROVIDER,
                null,
                null,
                executor,
                new SegmentIndexWriteBarrier(),
                new SegmentIndexAliasManager(null, config));
    }
}
