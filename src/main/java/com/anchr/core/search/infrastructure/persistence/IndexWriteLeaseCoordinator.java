package com.anchr.core.search.infrastructure.persistence;

import com.anchr.core.search.domain.model.EmbeddingDeploymentStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DB-backed short-lived write permits used to drain all application instances at cutover.
 */
@Component
public class IndexWriteLeaseCoordinator {

    private static final long LEASE_MINUTES = 5L;
    private static final long HEARTBEAT_SECONDS = 60L;
    private static final long DRAIN_TIMEOUT_SECONDS = 30L;
    private final EmbeddingDeploymentMapper mapper;
    private final TransactionTemplate requiresNew;
    private final String ownerId = UUID.randomUUID().toString();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "index-write-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    public IndexWriteLeaseCoordinator(
            EmbeddingDeploymentMapper mapper,
            PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String acquire() {
        String token = requiresNew.execute(status -> {
            EmbeddingDeploymentRecord control = mapper.findForShare();
            if (control != null && EmbeddingDeploymentStatus.CUTTING_OVER.name()
                    .equals(control.getDeploymentStatus())) {
                throw new IllegalStateException(
                        "Embedding index is in its short cutover write barrier");
            }
            mapper.deleteExpiredWriteLeases();
            String acquiredToken = UUID.randomUUID().toString();
            mapper.insertWriteLease(
                    acquiredToken, ownerId, LocalDateTime.now().plusMinutes(LEASE_MINUTES));
            return acquiredToken;
        });
        if (token == null) {
            throw new IllegalStateException("Failed to acquire index write lease");
        }
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> renew(token),
                HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
        heartbeats.put(token, heartbeat);
        return token;
    }

    public void assertActive(String token) {
        if (token == null) {
            throw new IllegalStateException("Index write lease is missing");
        }
        Long active = requiresNew.execute(status ->
                mapper.countActiveWriteLease(token, ownerId));
        if (active == null || active != 1L) {
            throw new IllegalStateException(
                    "Index write lease expired before the write completed");
        }
    }

    public void release(String token) {
        if (token == null) {
            return;
        }
        ScheduledFuture<?> heartbeat = heartbeats.remove(token);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        requiresNew.executeWithoutResult(status -> mapper.deleteWriteLease(token));
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    public void awaitDrained() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);
        while (true) {
            long active = requiresNew.execute(status -> {
                mapper.deleteExpiredWriteLeases();
                return mapper.countActiveWriteLeases();
            });
            if (active == 0L) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for " + active + " index writes to drain");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while draining index writes", e);
            }
        }
    }

    private void renew(String token) {
        try {
            Integer updated = requiresNew.execute(status -> mapper.renewWriteLease(
                    token, ownerId, LocalDateTime.now().plusMinutes(LEASE_MINUTES)));
            if (updated == null || updated != 1) {
                ScheduledFuture<?> heartbeat = heartbeats.remove(token);
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
            }
        } catch (RuntimeException ignored) {
            // The completion assertion is authoritative. A transient heartbeat
            // failure gets another chance while the existing lease is alive.
        }
    }
}
