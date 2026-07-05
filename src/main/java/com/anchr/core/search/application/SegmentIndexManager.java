package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;

public interface SegmentIndexManager {
    void asyncCreate();
    void createPendingRebuild(String reason, int expectedDim);
    SegmentIndexStatusDTO status();
    boolean retryCreate();
    boolean confirmRebuild(String taskId);
    String prepareRebuild();
}
