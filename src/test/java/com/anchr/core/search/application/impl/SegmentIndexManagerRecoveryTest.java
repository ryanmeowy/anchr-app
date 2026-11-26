package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentIndexManagerRecoveryTest {

    private static final EmbeddingProfile PROFILE =
            new EmbeddingProfile(1L, "EMBEDDING", "test-model", 1024, "test-profile");
    private static final String INDEX = "kb_segment_20260101000000";

    @Test
    void statusShouldRecoverAfterTransientTopologyFailure() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentIndexManagerImpl manager = newManager(topology, inspections, Runnable::run);

        SegmentIndexStatusDTO failed = manager.status();

        assertEquals(SegmentIndexStatus.NOT_READY, failed.getStatus());
        assertFalse(failed.isIndexExists());
        assertTrue(failed.getLastError().startsWith("Alias topology invalid: "));
        assertEquals(1, inspections.get());

        SegmentIndexStatusDTO stillFailed = manager.status();

        assertEquals(SegmentIndexStatus.NOT_READY, stillFailed.getStatus());
        assertEquals(1, inspections.get(),
                "NOT_READY must not inspect ES once per status call during the refresh interval");

        topology.set(validTopology());
        AtomicLong lastRefresh = (AtomicLong) ReflectionTestUtils.getField(
                manager, "lastAliasTopologyRefreshMs");
        lastRefresh.set(0L);

        SegmentIndexStatusDTO recovered = manager.status();

        assertEquals(SegmentIndexStatus.READY, recovered.getStatus());
        assertTrue(recovered.isIndexExists());
        assertTrue(recovered.isReadable());
        assertTrue(recovered.isWritable());
        assertNull(recovered.getLastError());
        assertEquals(2, inspections.get());
    }

    @Test
    void retryCreateShouldAdoptExistingAliasesWithoutCreatingAnotherIndex() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(topology, inspections, queuedTasks::add);

        assertEquals(SegmentIndexStatus.NOT_READY, manager.status().getStatus());

        topology.set(validTopology());

        assertTrue(manager.retryCreate());
        assertTrue(queuedTasks.isEmpty());

        SegmentIndexStatusDTO status = manager.status();
        assertEquals(SegmentIndexStatus.READY, status.getStatus());
        assertTrue(status.isIndexExists());
        assertNull(status.getLastError());
    }

    @Test
    void retryCreateShouldNotCreateWhileTopologyIsStillUnavailable() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(topology, inspections, queuedTasks::add);

        manager.status();

        assertFalse(manager.retryCreate());
        assertTrue(queuedTasks.isEmpty());
        assertEquals(SegmentIndexStatus.NOT_READY, manager.status().getStatus());
    }

    @Test
    void retryCreateShouldNotCreateWhenAliasesExistButTopologyIsInvalid() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(topology, inspections, queuedTasks::add);

        manager.status();
        topology.set(invalidAliasTopology());

        assertFalse(manager.retryCreate());
        assertTrue(queuedTasks.isEmpty());
    }

    @Test
    void retryCreateShouldNotReportRecoveryForAReadOnlyAliasTopology() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<>();
        SegmentIndexManagerImpl manager = newManager(topology, inspections, queuedTasks::add);

        manager.status();
        topology.set(new AliasTopology(
                true, true, false, true, false, INDEX, null,
                "readTargets=[" + INDEX + "], writeTargets=[]"));

        assertFalse(manager.retryCreate());
        assertTrue(queuedTasks.isEmpty());
    }

    @Test
    void createShouldRefuseToRunWhenAliasesAlreadyExist() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentIndexManagerImpl manager = newManager(topology, inspections, Runnable::run);

        manager.status();
        topology.set(invalidAliasTopology());

        manager.asyncCreate();

        // Keep the cached state so the assertion below observes the refusal error instead of
        // depending on the 15s refresh interval.
        AtomicLong lastRefresh = (AtomicLong) ReflectionTestUtils.getField(
                manager, "lastAliasTopologyRefreshMs");
        lastRefresh.set(System.currentTimeMillis());

        SegmentIndexStatusDTO status = manager.status();
        assertEquals(SegmentIndexStatus.NOT_READY, status.getStatus());
        assertNotNull(status.getLastError());
        assertTrue(status.getLastError().contains("aliases already exist"),
                () -> "unexpected error: " + status.getLastError());
    }

    @Test
    void createShouldRefuseToRunWhileTopologyIsUnavailable() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentIndexManagerImpl manager = newManager(topology, inspections, Runnable::run);

        manager.status();
        manager.asyncCreate();

        SegmentIndexStatusDTO status = manager.status();
        assertEquals(SegmentIndexStatus.NOT_READY, status.getStatus());
        assertNotNull(status.getLastError());
        assertTrue(status.getLastError().contains("alias topology is unavailable"),
                () -> "unexpected error: " + status.getLastError());
    }

    @Test
    void retryCreateShouldRecoverAfterARefusedCreateFoundExistingAliases() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentIndexManagerImpl manager = newManager(topology, inspections, Runnable::run);

        manager.status();
        topology.set(validTopology());

        manager.asyncCreate();

        assertEquals(SegmentIndexStatus.NOT_READY, manager.status().getStatus());

        assertTrue(manager.retryCreate());
        SegmentIndexStatusDTO status = manager.status();
        assertEquals(SegmentIndexStatus.READY, status.getStatus());
        assertNull(status.getLastError(),
                "The recovery must clear the previous create-refusal error");
    }

    @Test
    void statusShouldClearStaleCreateFailureAfterTopologyRecovers() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<AliasTopology> topology = new AtomicReference<>(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentIndexManagerImpl manager = newManager(topology, inspections, Runnable::run);

        assertEquals(SegmentIndexStatus.NOT_READY, manager.status().getStatus());

        topology.set(validTopology());
        manager.asyncCreate();

        SegmentIndexStatusDTO refused = manager.status();
        assertEquals(SegmentIndexStatus.NOT_READY, refused.getStatus());
        assertNotNull(refused.getLastError());
        assertFalse(refused.getLastError().startsWith("Alias topology invalid: "),
                () -> "expected a create-refusal error, got: " + refused.getLastError());

        AtomicLong lastRefresh = (AtomicLong) ReflectionTestUtils.getField(
                manager, "lastAliasTopologyRefreshMs");
        lastRefresh.set(0L);

        SegmentIndexStatusDTO recovered = manager.status();
        assertEquals(SegmentIndexStatus.READY, recovered.getStatus());
        assertTrue(recovered.isWritable());
        assertNull(recovered.getLastError(),
                "A promoted READY state must not keep the old create failure");
    }

    private static SegmentIndexManagerImpl newManager(
            AtomicReference<AliasTopology> topology,
            AtomicInteger inspections,
            Executor executor
    ) {
        SegmentIndexAliasManager aliasManager = new SegmentIndexAliasManager(null) {
            @Override
            public AliasTopology inspect() {
                inspections.incrementAndGet();
                return topology.get();
            }
        };
        return SegmentIndexManagerTestFactory.create(
                null,
                () -> Optional.of(PROFILE),
                null,
                null,
                null,
                executor,
                new SegmentIndexWriteBarrier(),
                aliasManager);
    }

    private static AliasTopology validTopology() {
        return new AliasTopology(true, true, true, true, true, INDEX, INDEX, null);
    }

    private static AliasTopology invalidAliasTopology() {
        return new AliasTopology(
                true, true, true, false, false, INDEX, null,
                "readTargets=[" + INDEX + "], writeTargets=[]");
    }
}
