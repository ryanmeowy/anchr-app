package com.anchr.core.search.infrastructure.persistence;

import com.anchr.core.search.domain.model.EmbeddingDeployment;
import com.anchr.core.search.domain.model.EmbeddingDeploymentStatus;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.PhysicalIndexProfile;
import com.anchr.core.search.domain.model.EmbeddingImpactReport;
import com.anchr.core.search.domain.repository.EmbeddingDeploymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EmbeddingDeploymentRepositoryImpl implements EmbeddingDeploymentRepository {

    private static final int MAX_ERROR_LENGTH = 2_000;
    private final EmbeddingDeploymentMapper mapper;

    @Override
    public Optional<EmbeddingDeployment> find() {
        return Optional.ofNullable(mapper.find()).map(this::toDomain);
    }

    @Override
    @Transactional
    public void initializeServing(EmbeddingProfile servingProfile, String physicalIndex) {
        if (servingProfile == null || physicalIndex == null || physicalIndex.isBlank()) {
            return;
        }
        EmbeddingDeploymentRecord record = initialRecord(servingProfile, physicalIndex);
        mapper.insertInitial(record);
        mapper.initializeMissingServing(record);
        mapper.upsertPhysicalProfile(
                physicalIndex, servingProfile.configId(), servingProfile.fingerprint(), servingProfile.capability(),
                servingProfile.modelName(), servingProfile.dimension(), 0L, "ACTIVE");
    }

    @Override
    @Transactional
    public void requestDesired(EmbeddingProfile desiredProfile) {
        if (desiredProfile == null) {
            throw new IllegalArgumentException("Desired embedding profile is required");
        }
        EmbeddingDeploymentRecord current = mapper.findForShare();
        if (current == null) {
            EmbeddingDeploymentRecord initial = new EmbeddingDeploymentRecord();
            setDesired(initial, desiredProfile);
            initial.setDeploymentStatus(EmbeddingDeploymentStatus.DESIRED.name());
            initial.setDeploymentVersion(0L);
            initial.setUpdatedAt(LocalDateTime.now());
            mapper.insertInitial(initial);
            return;
        }
        EmbeddingDeploymentRecord update = new EmbeddingDeploymentRecord();
        setDesired(update, desiredProfile);
        if (mapper.updateDesired(update) != 1) {
            throw new IllegalStateException(
                    "Embedding deployment is already running; desired profile cannot be replaced");
        }
    }

    @Override
    public boolean prepare(String taskId, EmbeddingProfile targetProfile, long expectedVersion) {
        EmbeddingDeployment deployment = find().orElse(null);
        return deployment != null
                && deployment.desiredProfile() != null
                && deployment.desiredProfile().fingerprint().equals(targetProfile.fingerprint())
                && mapper.prepare(taskId, expectedVersion) == 1;
    }

    @Override
    public boolean recordImpact(String taskId, EmbeddingImpactReport impactReport) {
        return mapper.recordImpact(
                taskId,
                impactReport.imageAssets(),
                impactReport.ocrAvailableAssets(),
                impactReport.ocrEmptyAssets(),
                impactReport.textVectorFailures(),
                impactReport.expectedVisualSemanticLossAssets(),
                impactReport.confirmationRequired(),
                impactReport.confirmed()) == 1;
    }

    @Override
    public boolean claim(String taskId, String ownerToken, LocalDateTime leaseUntil,
                         long startRevision, long expectedVersion) {
        return mapper.claim(taskId, ownerToken, leaseUntil, startRevision, expectedVersion) == 1;
    }

    @Override
    public boolean recordTarget(String taskId, String ownerToken, String targetPhysicalIndex,
                                long appliedRevision) {
        EmbeddingDeployment deployment = find().orElse(null);
        if (deployment == null || deployment.targetProfile() == null) {
            return false;
        }
        boolean updated = mapper.recordTarget(
                taskId, ownerToken, targetPhysicalIndex, appliedRevision) == 1;
        if (updated) {
            EmbeddingProfile profile = deployment.targetProfile();
            mapper.upsertPhysicalProfile(
                    targetPhysicalIndex, profile.configId(), profile.fingerprint(), profile.capability(),
                    profile.modelName(), profile.dimension(), appliedRevision, "BUILDING");
        }
        return updated;
    }

    @Override
    public boolean recordProgress(String taskId, String ownerToken, long appliedRevision,
                                  String status) {
        return mapper.recordProgress(taskId, ownerToken, appliedRevision, status) == 1;
    }

    @Override
    public boolean recordMigrationProgress(String taskId, String ownerToken,
                                           long migrated, long total, String phase) {
        return mapper.recordMigrationProgress(
                taskId, ownerToken, migrated, total, phase) == 1;
    }

    @Override
    public boolean beginCutover(String taskId, String ownerToken, long appliedRevision) {
        return mapper.beginCutover(taskId, ownerToken, appliedRevision) == 1;
    }

    @Override
    @Transactional
    public boolean activate(String taskId, String ownerToken, EmbeddingProfile servingProfile,
                            String servingPhysicalIndex, long appliedRevision) {
        boolean updated = mapper.activate(
                taskId, ownerToken, servingPhysicalIndex, appliedRevision) == 1;
        if (updated) {
            // The old active index served every committed change through the final
            // cutover watermark. Persist that fact before retaining it for rollback.
            mapper.markOtherPhysicalProfilesRollback(
                    servingPhysicalIndex, appliedRevision);
            mapper.upsertPhysicalProfile(
                    servingPhysicalIndex, servingProfile.configId(), servingProfile.fingerprint(), servingProfile.capability(),
                    servingProfile.modelName(), servingProfile.dimension(), appliedRevision, "ACTIVE");
        }
        return updated;
    }

    @Override
    public void fail(String taskId, String ownerToken, String error) {
        String clipped = error == null ? "Unknown deployment failure"
                : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        mapper.fail(taskId, ownerToken, clipped);
    }

    @Override
    public boolean isWriteAllowed() {
        return find().map(deployment ->
                deployment.status() != EmbeddingDeploymentStatus.CUTTING_OVER).orElse(true);
    }

    @Override
    public boolean isConfigProtected(Long configId) {
        if (configId == null) {
            return false;
        }
        boolean referencedByDeployment = find()
                .map(deployment -> deployment.protectsConfig(configId))
                .orElse(false);
        return referencedByDeployment
                || mapper.countProtectedPhysicalProfilesByConfigId(configId) > 0L;
    }

    @Override
    public Optional<PhysicalIndexProfile> findPhysicalProfile(String physicalIndex) {
        if (physicalIndex == null || physicalIndex.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findPhysicalProfile(physicalIndex))
                .map(record -> new PhysicalIndexProfile(
                        record.getPhysicalIndex(), record.getConfigId(), record.getProfileFingerprint(),
                        record.getCapability(), record.getModelName(),
                        record.getVectorSchemaVersion(), record.getDimension(),
                        record.getMaxAppliedRevision(), record.getLifecycleStatus()));
    }

    private EmbeddingDeploymentRecord initialRecord(
            EmbeddingProfile profile, String physicalIndex) {
        EmbeddingDeploymentRecord record = new EmbeddingDeploymentRecord();
        setDesired(record, profile);
        setServing(record, profile);
        record.setServingPhysicalIndex(physicalIndex);
        record.setDeploymentStatus(EmbeddingDeploymentStatus.ACTIVE.name());
        record.setDeploymentVersion(0L);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    private void setDesired(EmbeddingDeploymentRecord record, EmbeddingProfile profile) {
        record.setDesiredConfigId(profile.configId());
        record.setDesiredCapability(profile.capability());
        record.setDesiredModelName(profile.modelName());
        record.setDesiredDimension(profile.dimension());
        record.setDesiredFingerprint(profile.fingerprint());
    }

    private void setServing(EmbeddingDeploymentRecord record, EmbeddingProfile profile) {
        record.setServingConfigId(profile.configId());
        record.setServingCapability(profile.capability());
        record.setServingModelName(profile.modelName());
        record.setServingDimension(profile.dimension());
        record.setServingFingerprint(profile.fingerprint());
    }

    private EmbeddingDeployment toDomain(EmbeddingDeploymentRecord record) {
        return EmbeddingDeployment.builder()
                .desiredProfile(toProfile(
                        record.getDesiredConfigId(), record.getDesiredCapability(),
                        record.getDesiredModelName(), record.getDesiredDimension(),
                        record.getDesiredFingerprint()))
                .servingProfile(toProfile(
                        record.getServingConfigId(), record.getServingCapability(),
                        record.getServingModelName(), record.getServingDimension(),
                        record.getServingFingerprint()))
                .targetProfile(toProfile(
                        record.getTargetConfigId(), record.getTargetCapability(),
                        record.getTargetModelName(), record.getTargetDimension(),
                        record.getTargetFingerprint()))
                .servingPhysicalIndex(record.getServingPhysicalIndex())
                .targetPhysicalIndex(record.getTargetPhysicalIndex())
                .status(parseStatus(record.getDeploymentStatus()))
                .taskId(record.getTaskId())
                .startRevision(record.getStartRevision())
                .appliedRevision(record.getAppliedRevision())
                .rebuildMigrated(record.getRebuildMigrated())
                .rebuildTotal(record.getRebuildTotal())
                .rebuildPhase(record.getRebuildPhase())
                .impactReport(new EmbeddingImpactReport(
                        record.getImpactImageAssets(),
                        record.getImpactOcrAvailableAssets(),
                        record.getImpactOcrEmptyAssets(),
                        record.getImpactTextVectorFailures(),
                        record.getImpactVisualLossAssets(),
                        record.isImpactConfirmationRequired(),
                        record.isImpactConfirmed()))
                .impactReportReady(record.isImpactReportReady())
                .version(record.getDeploymentVersion())
                .ownerToken(record.getOwnerToken())
                .leaseUntil(record.getLeaseUntil())
                .lastError(record.getLastError())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private EmbeddingProfile toProfile(Long configId, String capability, String model,
                                       Integer dimension, String fingerprint) {
        if (capability == null || model == null || dimension == null || fingerprint == null) {
            return null;
        }
        return new EmbeddingProfile(configId, capability, model, dimension, fingerprint);
    }

    private EmbeddingDeploymentStatus parseStatus(String status) {
        return status == null ? EmbeddingDeploymentStatus.ACTIVE
                : EmbeddingDeploymentStatus.valueOf(status);
    }
}
