package com.anchr.core.search.application;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;

public interface SegmentIndexManager {
    void asyncCreate();
    SegmentIndexStatusDTO status();
    boolean retryCreate();
    boolean confirmRebuild(String taskId);
    String prepareRebuild();

    /** Registers an in-memory rebuild target without changing the active model. */
    String requestRebuild(EmbeddingProfile targetProfile);
}
