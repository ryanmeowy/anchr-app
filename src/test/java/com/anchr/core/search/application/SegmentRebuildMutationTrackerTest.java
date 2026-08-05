package com.anchr.core.search.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentRebuildMutationTrackerTest {

    @Test
    void shouldCoalesceAnAssetAtItsLatestMutationSequence() {
        SegmentRebuildMutationTracker tracker = new SegmentRebuildMutationTracker();
        tracker.start("task-1", 10);

        tracker.markDirty("asset-1");
        long first = tracker.snapshot("task-1").dirtyAssets().get("asset-1");
        tracker.markDirty("asset-1");
        long second = tracker.snapshot("task-1").dirtyAssets().get("asset-1");

        assertThat(second).isGreaterThan(first);
        assertThat(tracker.removeIfUnchanged("task-1", "asset-1", first)).isFalse();
        assertThat(tracker.removeIfUnchanged("task-1", "asset-1", second)).isTrue();
        assertThat(tracker.dirtyAssetCount("task-1")).isZero();
    }

    @Test
    void overflowShouldAbortOnlyTheRebuildTracker() {
        SegmentRebuildMutationTracker tracker = new SegmentRebuildMutationTracker();
        tracker.start("task-1", 1);

        tracker.markDirty("asset-1");
        tracker.markDirty("asset-2");

        assertThat(tracker.overflowed("task-1")).isTrue();
        assertThat(tracker.snapshot("task-1").dirtyAssets())
                .containsOnlyKeys("asset-1", "asset-2");
        tracker.stop("task-1");
        tracker.markDirty("asset-3");
        assertThat(tracker.isActive("task-1")).isFalse();
    }
}
