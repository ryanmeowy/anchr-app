package com.anchr.core.search.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates normal index writes with an in-process index rebuild.
 *
 * <p>Normal writes hold a shared permit from readiness validation through the
 * Elasticsearch request. A rebuild holds the exclusive permit until its final
 * state transition, so no accepted write can land in the old index after the
 * rebuild snapshot is taken.</p>
 */
@Component
public class SegmentIndexWriteBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public void withWritePermit(Runnable action) {
        runWithLock(lock.readLock(), action);
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
