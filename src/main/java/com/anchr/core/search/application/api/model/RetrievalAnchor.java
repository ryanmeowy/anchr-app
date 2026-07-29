package com.anchr.core.search.application.api.model;

import com.anchr.core.common.model.BboxInfo;

import java.util.List;

public record RetrievalAnchor(
        Integer pageNo,
        Integer chunkOrder,
        List<BboxInfo> bbox,
        Integer imageWidth,
        Integer imageHeight
) {
    public RetrievalAnchor {
        bbox = bbox == null ? null : List.copyOf(bbox);
    }
}
