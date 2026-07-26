package com.anchr.core.search.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmbeddingDeploymentRecord {
    private Long desiredConfigId;
    private String desiredCapability;
    private String desiredModelName;
    private Integer desiredDimension;
    private String desiredFingerprint;
    private Long servingConfigId;
    private String servingCapability;
    private String servingModelName;
    private Integer servingDimension;
    private String servingFingerprint;
    private String servingPhysicalIndex;
    private Long targetConfigId;
    private String targetCapability;
    private String targetModelName;
    private Integer targetDimension;
    private String targetFingerprint;
    private String targetPhysicalIndex;
    private String deploymentStatus;
    private String taskId;
    private long startRevision;
    private long appliedRevision;
    private long rebuildMigrated;
    private long rebuildTotal;
    private String rebuildPhase;
    private long impactImageAssets;
    private long impactOcrAvailableAssets;
    private long impactOcrEmptyAssets;
    private long impactTextVectorFailures;
    private long impactVisualLossAssets;
    private boolean impactReportReady;
    private boolean impactConfirmationRequired;
    private boolean impactConfirmed;
    private long deploymentVersion;
    private String ownerToken;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime updatedAt;
}
