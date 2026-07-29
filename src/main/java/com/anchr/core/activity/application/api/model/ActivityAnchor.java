package com.anchr.core.activity.application.api.model;

import java.util.List;

/** Activity-owned preview anchor snapshot. */
public record ActivityAnchor(Integer pageNo, Integer chunkOrder, List<ActivityBbox> bbox,
                             Integer imageWidth, Integer imageHeight) {
    public ActivityAnchor {
        bbox = bbox == null || bbox.isEmpty() ? List.of() : List.copyOf(bbox);
    }

    public record ActivityBbox(Bbox bbox, int pageNo) {
    }

    public record Bbox(double l, double t, double r, double b, String coordOrigin) {
    }
}
