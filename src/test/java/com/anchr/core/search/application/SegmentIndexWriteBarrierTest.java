package com.anchr.core.search.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentIndexWriteBarrierTest {

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
