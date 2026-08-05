package com.anchr.core.search.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks assets changed while a single-process segment index rebuild is active.
 *
 * <p>The tracker deliberately stores only asset identifiers and their latest
 * mutation sequence. The rebuild reads the authoritative state back from the
 * serving physical index during catch-up, so normal writes never wait for the
 * target embedding model or target index.</p>
 */
@Component
public class SegmentRebuildMutationTracker {

    private final AtomicReference<TrackingState> stateRef = new AtomicReference<>();

    public void start(String taskId, int dirtyAssetLimit) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("Rebuild task id is required");
        }
        TrackingState next = new TrackingState(taskId, Math.max(1, dirtyAssetLimit));
        if (!stateRef.compareAndSet(null, next)) {
            throw new IllegalStateException("A rebuild mutation tracker is already active");
        }
    }

    /** Marks an asset dirty without propagating tracker failures to normal ingestion. */
    public void markDirty(String assetId) {
        if (!StringUtils.hasText(assetId)) {
            return;
        }
        TrackingState state = stateRef.get();
        if (state == null) {
            return;
        }
        long sequence = state.sequence.incrementAndGet();
        state.dirtyAssets.put(assetId.trim(), sequence);
        if (state.dirtyAssets.size() > state.dirtyAssetLimit) {
            state.overflowed.set(true);
        }
    }

    public Snapshot snapshot(String taskId) {
        TrackingState state = requireState(taskId);
        return new Snapshot(
                state.sequence.get(),
                new LinkedHashMap<>(state.dirtyAssets),
                state.overflowed.get());
    }

    public boolean removeIfUnchanged(String taskId, String assetId, long version) {
        TrackingState state = requireState(taskId);
        return state.dirtyAssets.remove(assetId, version);
    }

    public long sequence(String taskId) {
        return requireState(taskId).sequence.get();
    }

    public int dirtyAssetCount(String taskId) {
        return requireState(taskId).dirtyAssets.size();
    }

    public boolean overflowed(String taskId) {
        return requireState(taskId).overflowed.get();
    }

    public void stop(String taskId) {
        TrackingState state = stateRef.get();
        if (state != null && state.taskId.equals(taskId)) {
            stateRef.compareAndSet(state, null);
        }
    }

    public boolean isActive(String taskId) {
        TrackingState state = stateRef.get();
        return state != null && state.taskId.equals(taskId);
    }

    private TrackingState requireState(String taskId) {
        TrackingState state = stateRef.get();
        if (state == null || !state.taskId.equals(taskId)) {
            throw new IllegalStateException("Rebuild mutation tracker is not active: " + taskId);
        }
        return state;
    }

    public record Snapshot(
            long sequence,
            Map<String, Long> dirtyAssets,
            boolean overflowed
    ) {
    }

    private static final class TrackingState {
        private final String taskId;
        private final int dirtyAssetLimit;
        private final AtomicLong sequence = new AtomicLong();
        private final ConcurrentHashMap<String, Long> dirtyAssets = new ConcurrentHashMap<>();
        private final AtomicBoolean overflowed = new AtomicBoolean();

        private TrackingState(String taskId, int dirtyAssetLimit) {
            this.taskId = taskId;
            this.dirtyAssetLimit = dirtyAssetLimit;
        }
    }
}
