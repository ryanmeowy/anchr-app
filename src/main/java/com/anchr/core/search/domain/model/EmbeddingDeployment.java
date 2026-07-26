package com.anchr.core.search.domain.model;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Persistent control-plane snapshot for embedding profile deployment.
 *
 * <p>The serving profile is the profile bound to the read/write aliases. Desired is an
 * administrator request and target is the immutable snapshot owned by an in-flight rebuild.</p>
 */
@Builder(toBuilder = true)
public record EmbeddingDeployment(
        EmbeddingProfile desiredProfile,
        EmbeddingProfile servingProfile,
        EmbeddingProfile targetProfile,
        String servingPhysicalIndex,
        String targetPhysicalIndex,
        EmbeddingDeploymentStatus status,
        String taskId,
        long startRevision,
        long appliedRevision,
        long rebuildMigrated,
        long rebuildTotal,
        String rebuildPhase,
        EmbeddingImpactReport impactReport,
        boolean impactReportReady,
        long version,
        String ownerToken,
        LocalDateTime leaseUntil,
        String lastError,
        LocalDateTime updatedAt
) {
    public boolean deploymentInProgress() {
        return status == EmbeddingDeploymentStatus.BACKFILLING
                || status == EmbeddingDeploymentStatus.VALIDATING
                || status == EmbeddingDeploymentStatus.CUTTING_OVER;
    }

    public boolean protectsConfig(Long configId) {
        if (configId == null) {
            return false;
        }
        return hasConfig(desiredProfile, configId)
                || hasConfig(servingProfile, configId)
                || hasConfig(targetProfile, configId);
    }

    private static boolean hasConfig(EmbeddingProfile profile, Long configId) {
        return profile != null && configId.equals(profile.configId());
    }
}
