package com.anchr.core.search.application;

import com.anchr.core.search.infrastructure.persistence.IndexWriteLeaseCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SegmentIndexWriteBarrierTest {

    @Test
    void transactionBoundLeaseShouldReleaseOnlyAfterTransactionCompletion() {
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        IndexWriteLeaseCoordinator coordinator = mock(IndexWriteLeaseCoordinator.class);
        barrier.setDistributedCoordinator(coordinator);
        org.mockito.Mockito.when(coordinator.acquire()).thenReturn("lease-tx");
        TransactionSynchronizationManager.initSynchronization();
        try {
            barrier.bindDistributedLeaseToCurrentTransaction();
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();

            verify(coordinator, never()).release("lease-tx");
            synchronizations.forEach(sync -> sync.beforeCommit(false));
            verify(coordinator).assertActive("lease-tx");
            synchronizations.forEach(sync -> sync.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
            verify(coordinator).release("lease-tx");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void distributedLeaseShouldRemainActiveUntilWriteCompletes() {
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        IndexWriteLeaseCoordinator coordinator = mock(IndexWriteLeaseCoordinator.class);
        barrier.setDistributedCoordinator(coordinator);
        org.mockito.Mockito.when(coordinator.acquire()).thenReturn("lease-1");

        barrier.withWritePermit(() -> { });

        var order = inOrder(coordinator);
        order.verify(coordinator).acquire();
        order.verify(coordinator).assertActive("lease-1");
        order.verify(coordinator).release("lease-1");
    }

    @Test
    void expiredDistributedLeaseShouldFailWriteAndStillRelease() {
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        IndexWriteLeaseCoordinator coordinator = mock(IndexWriteLeaseCoordinator.class);
        barrier.setDistributedCoordinator(coordinator);
        org.mockito.Mockito.when(coordinator.acquire()).thenReturn("lease-2");
        doThrow(new IllegalStateException("expired"))
                .when(coordinator).assertActive("lease-2");

        assertThrows(IllegalStateException.class,
                () -> barrier.withWritePermit(() -> { }));

        var order = inOrder(coordinator);
        order.verify(coordinator).acquire();
        order.verify(coordinator).assertActive("lease-2");
        order.verify(coordinator).release("lease-2");
    }

    @Test
    void exclusiveRebuildPermitShouldWaitForAcceptedWrite() throws Exception {
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch rebuildEntered = new CountDownLatch(1);

        try {
            Future<?> write = executor.submit(() -> barrier.withWritePermit(() -> {
                writeEntered.countDown();
                await(releaseWrite);
            }));
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));

            Future<?> rebuild = executor.submit(() ->
                    barrier.withExclusiveRebuildPermit(rebuildEntered::countDown));

            assertFalse(rebuildEntered.await(200, TimeUnit.MILLISECONDS));
            releaseWrite.countDown();
            assertTrue(rebuildEntered.await(2, TimeUnit.SECONDS));
            write.get(2, TimeUnit.SECONDS);
            rebuild.get(2, TimeUnit.SECONDS);
        } finally {
            releaseWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void writePermitShouldWaitForActiveRebuild() throws Exception {
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch rebuildEntered = new CountDownLatch(1);
        CountDownLatch releaseRebuild = new CountDownLatch(1);
        CountDownLatch writeEntered = new CountDownLatch(1);

        try {
            Future<?> rebuild = executor.submit(() ->
                    barrier.withExclusiveRebuildPermit(() -> {
                        rebuildEntered.countDown();
                        await(releaseRebuild);
                    }));
            assertTrue(rebuildEntered.await(2, TimeUnit.SECONDS));

            Future<?> write = executor.submit(() ->
                    barrier.withWritePermit(writeEntered::countDown));

            assertFalse(writeEntered.await(200, TimeUnit.MILLISECONDS));
            releaseRebuild.countDown();
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            rebuild.get(2, TimeUnit.SECONDS);
            write.get(2, TimeUnit.SECONDS);
        } finally {
            releaseRebuild.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting in test", e);
        }
    }
}
