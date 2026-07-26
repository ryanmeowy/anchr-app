package com.anchr.core.search.application;

import com.anchr.core.search.domain.model.IndexRuntimeSnapshot;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;

public interface SegmentIndexManager {
    void asyncCreate();
    SegmentIndexStatusDTO status();
    boolean retryCreate();
    boolean confirmRebuild(String taskId);
    String prepareRebuild();

    default IndexRuntimeSnapshot runtimeSnapshot() {
        throw new IllegalStateException("Index runtime snapshot is unavailable");
    }

    default boolean rollback(String physicalIndex) {
        return false;
    }
}
