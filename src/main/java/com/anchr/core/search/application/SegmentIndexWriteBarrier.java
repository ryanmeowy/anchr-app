package com.anchr.core.search.application;

import com.anchr.core.search.infrastructure.persistence.IndexWriteLeaseCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
@Slf4j
public class SegmentIndexWriteBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private IndexWriteLeaseCoordinator distributedCoordinator;

    @Autowired(required = false)
    void setDistributedCoordinator(IndexWriteLeaseCoordinator distributedCoordinator) {
        this.distributedCoordinator = distributedCoordinator;
    }

    public void withWritePermit(Runnable action) {
        runWithLock(lock.readLock(), () -> {
            String lease = distributedCoordinator == null
                    ? null : distributedCoordinator.acquire();
            try {
                action.run();
                if (distributedCoordinator != null) {
                    distributedCoordinator.assertActive(lease);
                }
            } finally {
                if (distributedCoordinator != null) {
                    distributedCoordinator.release(lease);
                }
            }
        });
    }

    public void withExclusiveRebuildPermit(Runnable action) {
        runWithLock(lock.writeLock(), action);
    }

    public void awaitDistributedWritesDrained() {
        if (distributedCoordinator != null) {
            distributedCoordinator.awaitDrained();
        }
    }

    /**
     * Keeps a distributed write lease until the caller's database transaction has
     * committed its asset-index change record. This closes the gap between a
     * successful Elasticsearch bulk request and the revision becoming visible to
     * the cutover catch-up reader.
     */
    public void bindDistributedLeaseToCurrentTransaction() {
        if (distributedCoordinator == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "An active transaction is required for a transaction-bound index write lease");
        }
        String lease = distributedCoordinator.acquire();
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            distributedCoordinator.assertActive(lease);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            try {
                                distributedCoordinator.release(lease);
                            } catch (RuntimeException e) {
                                log.warn("Failed to release transaction-bound index write lease", e);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            distributedCoordinator.release(lease);
            throw e;
        }
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
