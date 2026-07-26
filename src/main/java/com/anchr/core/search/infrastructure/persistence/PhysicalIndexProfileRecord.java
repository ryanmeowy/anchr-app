package com.anchr.core.search.infrastructure.persistence;

import lombok.Data;

@Data
public class PhysicalIndexProfileRecord {
    private String physicalIndex;
    private Long configId;
    private String profileFingerprint;
    private String capability;
    private String modelName;
    private int vectorSchemaVersion;
    private int dimension;
    private long maxAppliedRevision;
    private String lifecycleStatus;
}
