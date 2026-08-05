package com.anchr.core.search.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordinates normal index writes with an in-process index rebuild.
 *
 * <p>Normal writes hold a shared permit from readiness validation through the
 * Elasticsearch request. Online rebuilds take the exclusive permit only while
 * enabling mutation capture and during the final alias/profile cutover. The
 * backfill and catch-up work run without this barrier.</p>
 */
@Component
public class SegmentIndexWriteBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public void withWritePermit(Runnable action) {
        runWithLock(lock.readLock(), action);
    }

    public <T> T withWritePermit(Supplier<T> action) {
        Lock targetLock = lock.readLock();
        targetLock.lock();
        try {
            return action.get();
        } finally {
            targetLock.unlock();
        }
    }

    public void withExclusiveRebuildPermit(Runnable action) {
        runWithLock(lock.writeLock(), action);
    }

    private void runWithLock(Lock targetLock, Runnable action) {
        targetLock.lock();
        try {
            action.run();
        } finally {
            targetLock.unlock();
        }
    }
}
