package com.anchr.core.search.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface EmbeddingDeploymentMapper {
    EmbeddingDeploymentRecord find();
    EmbeddingDeploymentRecord findForShare();
    int insertInitial(EmbeddingDeploymentRecord record);
    int initializeMissingServing(EmbeddingDeploymentRecord record);
    int updateDesired(EmbeddingDeploymentRecord record);
    int prepare(@Param("taskId") String taskId,
                @Param("expectedVersion") long expectedVersion);
    int recordImpact(@Param("taskId") String taskId,
                     @Param("imageAssets") long imageAssets,
                     @Param("ocrAvailableAssets") long ocrAvailableAssets,
                     @Param("ocrEmptyAssets") long ocrEmptyAssets,
                     @Param("textVectorFailures") long textVectorFailures,
                     @Param("visualLossAssets") long visualLossAssets,
                     @Param("confirmationRequired") boolean confirmationRequired,
                     @Param("confirmed") boolean confirmed);
    int claim(@Param("taskId") String taskId,
              @Param("ownerToken") String ownerToken,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("startRevision") long startRevision,
              @Param("expectedVersion") long expectedVersion);
    int recordTarget(@Param("taskId") String taskId,
                     @Param("ownerToken") String ownerToken,
                     @Param("targetPhysicalIndex") String targetPhysicalIndex,
                     @Param("appliedRevision") long appliedRevision);
    int recordProgress(@Param("taskId") String taskId,
                       @Param("ownerToken") String ownerToken,
                       @Param("appliedRevision") long appliedRevision,
                       @Param("status") String status);
    int recordMigrationProgress(@Param("taskId") String taskId,
                                @Param("ownerToken") String ownerToken,
                                @Param("migrated") long migrated,
                                @Param("total") long total,
                                @Param("phase") String phase);
    int beginCutover(@Param("taskId") String taskId,
                     @Param("ownerToken") String ownerToken,
                     @Param("appliedRevision") long appliedRevision);
    int activate(@Param("taskId") String taskId,
                 @Param("ownerToken") String ownerToken,
                 @Param("servingPhysicalIndex") String servingPhysicalIndex,
                 @Param("appliedRevision") long appliedRevision);
    int fail(@Param("taskId") String taskId,
             @Param("ownerToken") String ownerToken,
             @Param("error") String error);

    int deleteExpiredWriteLeases();
    int insertWriteLease(@Param("leaseToken") String leaseToken,
                         @Param("ownerId") String ownerId,
                         @Param("expiresAt") LocalDateTime expiresAt);
    int renewWriteLease(@Param("leaseToken") String leaseToken,
                        @Param("ownerId") String ownerId,
                        @Param("expiresAt") LocalDateTime expiresAt);
    int deleteWriteLease(@Param("leaseToken") String leaseToken);
    long countActiveWriteLease(@Param("leaseToken") String leaseToken,
                               @Param("ownerId") String ownerId);
    long countActiveWriteLeases();

    int upsertPhysicalProfile(@Param("physicalIndex") String physicalIndex,
                              @Param("configId") Long configId,
                              @Param("fingerprint") String fingerprint,
                              @Param("capability") String capability,
                              @Param("modelName") String modelName,
                              @Param("dimension") int dimension,
                              @Param("appliedRevision") long appliedRevision,
                              @Param("status") String status);
    int markOtherPhysicalProfilesRollback(
            @Param("activePhysicalIndex") String activePhysicalIndex,
            @Param("appliedRevision") long appliedRevision);
    long countProtectedPhysicalProfilesByConfigId(@Param("configId") Long configId);
    PhysicalIndexProfileRecord findPhysicalProfile(@Param("physicalIndex") String physicalIndex);
}
