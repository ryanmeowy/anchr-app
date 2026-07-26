package com.anchr.core.search.domain.repository;

import com.anchr.core.search.domain.model.EmbeddingDeployment;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.PhysicalIndexProfile;
import com.anchr.core.search.domain.model.EmbeddingImpactReport;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Cross-instance persistence boundary for the embedding deployment control plane.
 */
public interface EmbeddingDeploymentRepository {

    Optional<EmbeddingDeployment> find();

    void initializeServing(EmbeddingProfile servingProfile, String physicalIndex);

    void requestDesired(EmbeddingProfile desiredProfile);

    boolean prepare(String taskId, EmbeddingProfile targetProfile, long expectedVersion);

    boolean recordImpact(String taskId, EmbeddingImpactReport impactReport);

    boolean claim(String taskId, String ownerToken, LocalDateTime leaseUntil,
                  long startRevision, long expectedVersion);

    boolean recordTarget(String taskId, String ownerToken, String targetPhysicalIndex,
                         long appliedRevision);

    boolean recordProgress(String taskId, String ownerToken, long appliedRevision,
                           String status);

    boolean recordMigrationProgress(String taskId, String ownerToken,
                                    long migrated, long total, String phase);

    boolean beginCutover(String taskId, String ownerToken, long appliedRevision);

    boolean activate(String taskId, String ownerToken, EmbeddingProfile servingProfile,
                     String servingPhysicalIndex, long appliedRevision);

    void fail(String taskId, String ownerToken, String error);

    boolean isWriteAllowed();

    boolean isConfigProtected(Long configId);

    Optional<PhysicalIndexProfile> findPhysicalProfile(String physicalIndex);
}
