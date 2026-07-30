package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.acl.RetrievalCapabilityAcl;
import com.anchr.core.search.application.api.RetrievalEmbeddingDeploymentApi;
import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class SegmentIndexManagerImpl implements SegmentIndexManager, RetrievalEmbeddingDeploymentApi {

    private static final long ALIAS_TOPOLOGY_REFRESH_INTERVAL_MS = 15_000L;
    private static final String ALIAS_TOPOLOGY_ERROR_PREFIX = "Alias topology invalid: ";

    private final ElasticsearchClient esClient;
    private final SegmentIndexConfig kbSegmentConfig;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final SearchEmbeddingPort embeddingPort;

    @Qualifier("indexInitExecutor")
    private final Executor indexInitExecutor;
    private final SegmentIndexWriteBarrier indexWriteBarrier;
    private final SegmentIndexAliasManager aliasManager;
    private final SegmentIndexTopologyInspector topologyInspector;
    private final SegmentPhysicalIndexFactory physicalIndexFactory;
    private final SegmentIndexMigrationRunner migrationRunner;
    private final SegmentIndexStatusAssembler statusAssembler;
    private RetrievalCapabilityAcl retrievalCapabilityAcl;

    @Autowired(required = false)
    void setRetrievalCapabilityAcl(RetrievalCapabilityAcl retrievalCapabilityAcl) {
        this.retrievalCapabilityAcl = retrievalCapabilityAcl;
    }

    // Instance-level lock; use a distributed lock for multi-instance deployments.
    private final ReentrantLock indexOpLock = new ReentrantLock();

    // Instance-local lifecycle state; persist or externalize it before running multiple app instances.
    private final AtomicReference<SegmentIndexLifecycleState> stateRef =
            new AtomicReference<>(SegmentIndexLifecycleState.initial());
    private final AtomicLong lastAliasTopologyRefreshMs = new AtomicLong(0);

    public SegmentIndexManagerImpl(
            ElasticsearchClient esClient,
            SegmentIndexConfig kbSegmentConfig,
            EmbeddingProfileProvider embeddingProfileProvider,
            SearchEmbeddingPort embeddingPort,
            SearchObjectStoragePort storagePort,
            IdGen idGen,
            @Qualifier("indexInitExecutor") Executor indexInitExecutor,
            SegmentIndexWriteBarrier indexWriteBarrier,
            SegmentIndexAliasManager aliasManager
    ) {
        this.esClient = esClient;
        this.kbSegmentConfig = kbSegmentConfig;
        this.embeddingProfileProvider = embeddingProfileProvider;
        this.embeddingPort = embeddingPort;
        this.indexInitExecutor = indexInitExecutor;
        this.indexWriteBarrier = indexWriteBarrier;
        this.aliasManager = aliasManager;
        this.topologyInspector =
                new SegmentIndexTopologyInspector(esClient, aliasManager);
        this.physicalIndexFactory =
                new SegmentPhysicalIndexFactory(esClient, kbSegmentConfig);
        this.migrationRunner =
                new SegmentIndexMigrationRunner(esClient, storagePort, idGen);
        this.statusAssembler = new SegmentIndexStatusAssembler();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        SegmentIndexStatusDTO s = status();
        if (!s.isIndexExists()) {
            embeddingProfileProvider.getActiveEmbeddingProfile().ifPresentOrElse(
                    profile -> {
                        log.info("Boot: index missing, active embedding dim={}, triggering async create",
                                profile.dimension());
                        if (!tryScheduleCreate(profile)) {
                            log.info("Boot: index create was not scheduled");
                        }
                    },
                    () -> log.info("Boot: index missing but no active embedding configured, skipping")
            );
        } else {
            markReadyFromStatus(s);
            log.info("Boot: index exists via alias [{}], actualDim={}, expectedDim={}",
                    kbSegmentConfig.getReadTargetName(), s.getActualDim(), s.getExpectedDim());
        }
    }

    void markReadyFromStatus(SegmentIndexStatusDTO status) {
        SegmentIndexLifecycleState updated = stateRef.updateAndGet(current -> {
            if (current.status() != SegmentIndexStatus.NOT_READY) {
                log.info("markReadyFromStatus skipped: current status is {}", current.status());
                return current;
            }
            return current
                    .withStatus(SegmentIndexStatus.READY, current.lastError())
                    .withIndexInfo(
                            status.isIndexExists(),
                            null,
                            status.isReadable(),
                            status.isWritable(),
                            status.getActualDim(),
                            status.getActualModel(),
                            status.getActualProfileFingerprint());
        });
        if (updated.status() == SegmentIndexStatus.READY && updated.indexExists() != null) {
            lastAliasTopologyRefreshMs.set(System.currentTimeMillis());
        }
    }

    @Override
    public void asyncCreate() {
        if (!tryScheduleCreate()) {
            log.info("Create not scheduled because index state is not NOT_READY");
        }
    }

    private boolean tryScheduleCreate() {
        EmbeddingProfile profile = embeddingProfileProvider.getActiveEmbeddingProfile()
                .orElse(null);
        if (profile == null) {
            return false;
        }
        return tryScheduleCreate(profile);
    }

    private boolean tryScheduleCreate(EmbeddingProfile profile) {
        while (true) {
            SegmentIndexLifecycleState current = stateRef.get();
            if (current.status() != SegmentIndexStatus.NOT_READY) {
                return false;
            }
            SegmentIndexLifecycleState claimed =
                    current.withStatus(SegmentIndexStatus.INITIALIZING, null);
            if (!stateRef.compareAndSet(current, claimed)) {
                continue;
            }
            try {
                indexInitExecutor.execute(() -> executeCreate(profile));
                return true;
            } catch (RuntimeException e) {
                rollbackCreateClaim(e.getMessage());
                log.error("Failed to schedule index create: {}", e.getMessage(), e);
                return false;
            }
        }
    }

    private void executeCreate(EmbeddingProfile profile) {
        indexOpLock.lock();
        try {
            doCreate(profile);
            stateRef.updateAndGet(current ->
                    current.status() == SegmentIndexStatus.INITIALIZING
                            ? current.createSucceeded(profile)
                            : current);
            log.info("Index create completed, status=READY");
        } catch (Exception e) {
            stateRef.updateAndGet(current ->
                    current.status() == SegmentIndexStatus.INITIALIZING
                            ? current.withStatus(SegmentIndexStatus.NOT_READY, e.getMessage())
                            : current);
            log.error("Index create failed: {}", e.getMessage(), e);
        } finally {
            indexOpLock.unlock();
        }
    }

    private void rollbackCreateClaim(String error) {
        stateRef.updateAndGet(current ->
                current.status() == SegmentIndexStatus.INITIALIZING
                        ? current.withStatus(SegmentIndexStatus.NOT_READY, error)
                        : current);
    }

    private void doCreate(EmbeddingProfile profile) throws Exception {
        String physicalIndexName = physicalIndexFactory.newPhysicalIndexName();
        log.info("Create: building index [{}] with dim={}",
                physicalIndexName, profile.dimension());
        physicalIndexFactory.create(physicalIndexName, profile);
        try {
            aliasManager.bindAliases(physicalIndexName);
        } catch (Exception e) {
            cleanupFailedTargetIndex(
                    physicalIndexName,
                    kbSegmentConfig.getReadAlias(),
                    kbSegmentConfig.getWriteAlias());
            throw e;
        }
    }

    private String createPendingRebuildTask(String reason, EmbeddingProfile targetProfile) {
        String readAlias = kbSegmentConfig.getReadAlias();
        String writeAlias = kbSegmentConfig.getWriteAlias();
        if (!StringUtils.hasText(readAlias) || !StringUtils.hasText(writeAlias)) {
            throw new IllegalStateException(
                    "Read/write aliases must be configured for rebuild. " +
                            "Please set app.segment.read-alias and app.segment.write-alias.");
        }

        String taskId = UUID.randomUUID().toString();
        SegmentIndexPendingRebuild pending = new SegmentIndexPendingRebuild(
                taskId,
                targetProfile,
                reason,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        while (true) {
            SegmentIndexLifecycleState current = stateRef.get();
            if (current.status() != SegmentIndexStatus.READY) {
                throw new IllegalStateException(
                        "Cannot create rebuild task while index status is " + current.status());
            }
            SegmentIndexPendingRebuild existing = current.pendingRebuild();
            if (existing != null
                    && existing.targetProfile().fingerprint().equals(targetProfile.fingerprint())) {
                return existing.taskId();
            }
            if (stateRef.compareAndSet(current, current.withPendingRebuild(pending))) {
                if (existing == null) {
                    log.info("Pending rebuild task created: taskId={}, expectedDim={}, reason={}",
                            taskId, targetProfile.dimension(), reason);
                } else {
                    log.warn("Pending rebuild task replaced: oldTaskId={}, newTaskId={}, oldFingerprint={}, newFingerprint={}, oldReason={}, newReason={}",
                            existing.taskId(),
                            taskId,
                            existing.targetProfile().fingerprint(),
                            targetProfile.fingerprint(),
                            existing.reason(),
                            reason);
                }
                return taskId;
            }
        }
    }

    @Override
    public String requestRebuild(EmbeddingProfile targetProfile) {
        if (targetProfile == null) {
            throw new IllegalArgumentException("Target embedding profile is required");
        }
        SegmentIndexLifecycleState current = stateRef.get();
        if (Objects.equals(
                current.actualProfileFingerprint(), targetProfile.fingerprint())) {
            return null;
        }
        String reason = "Embedding model switch requested: "
                + Objects.toString(current.actualModel(), "unknown")
                + " -> " + targetProfile.modelName();
        return createPendingRebuildTask(reason, targetProfile);
    }

    @Override
    public String requestDeployment(RetrievalEmbeddingDeploymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Target embedding deployment is required");
        }
        return requestRebuild(new EmbeddingProfile(
                request.configId(),
                request.capability(),
                request.modelName(),
                request.dimension(),
                request.fingerprint()));
    }

    @Override
    public boolean confirmRebuild(String taskId) {
        RebuildClaim claim = tryClaimRebuild(taskId);
        if (claim == null) {
            log.warn("Rebuild confirm: task not found or mismatched, taskId={}", taskId);
            return false;
        }
        try {
            indexInitExecutor.execute(() -> executeRebuild(claim));
            return true;
        } catch (RuntimeException e) {
            rollbackRebuildClaim(claim, e.getMessage());
            log.error("Failed to schedule rebuild, taskId={}: {}", taskId, e.getMessage(), e);
            return false;
        }
    }

    private RebuildClaim tryClaimRebuild(String taskId) {
        while (true) {
            SegmentIndexLifecycleState current = stateRef.get();
            SegmentIndexPendingRebuild pending = current.pendingRebuild();
            if (current.status() != SegmentIndexStatus.READY
                    || pending == null
                    || !pending.taskId().equals(taskId)) {
                return null;
            }
            if (!pendingRebuildStillNeeded(current)) {
                clearObsoletePendingRebuild(current);
                return null;
            }
            SegmentIndexLifecycleState claimed = current.claimRebuild();
            if (stateRef.compareAndSet(current, claimed)) {
                return new RebuildClaim(taskId, pending.targetProfile());
            }
        }
    }

    private void executeRebuild(RebuildClaim claim) {
        indexOpLock.lock();
        try {
            indexWriteBarrier.withExclusiveRebuildPermit(() -> executeRebuildExclusively(claim));
        } finally {
            indexOpLock.unlock();
        }
    }

    private void executeRebuildExclusively(RebuildClaim claim) {
        try {
            EmbeddingSession embeddingSession = embeddingPort.openSession(claim.targetProfile());
            doRebuild(claim.targetProfile(), embeddingSession);
            stateRef.updateAndGet(current ->
                    isActiveRebuild(current, claim)
                            ? current.rebuildSucceeded(claim.targetProfile())
                            : current);
            log.info("Rebuild completed, taskId={}, status=READY", claim.taskId());
        } catch (Exception e) {
            AliasTopology topology = aliasManager.inspect();
            stateRef.updateAndGet(current ->
                    isActiveRebuild(current, claim)
                            ? current.rebuildFailed(
                            e.getMessage(), topology.readable(), topology.writable())
                            : current);
            log.error("Rebuild failed, taskId={}: {}", claim.taskId(), e.getMessage(), e);
        }
    }

    private void rollbackRebuildClaim(RebuildClaim claim, String error) {
        AliasTopology topology = aliasManager.inspect();
        stateRef.updateAndGet(current ->
                isActiveRebuild(current, claim)
                        ? current.rebuildFailed(error, topology.readable(), topology.writable())
                        : current);
    }

    private boolean isActiveRebuild(
            SegmentIndexLifecycleState state,
            RebuildClaim claim
    ) {
        return state.status() == SegmentIndexStatus.REBUILDING
                && state.pendingRebuild() != null
                && state.pendingRebuild().taskId().equals(claim.taskId());
    }

    private record RebuildClaim(String taskId, EmbeddingProfile targetProfile) {
    }

    private void doRebuild(EmbeddingProfile targetProfile, EmbeddingSession embeddingSession)
            throws Exception {
        String readAlias = kbSegmentConfig.getReadAlias();
        String writeAlias = kbSegmentConfig.getWriteAlias();

        // 1. 严格校验 read/write alias 均唯一且指向同一物理索引
        AliasTopology aliasTopology = aliasManager.requireValid();
        String oldPhysicalIndex = aliasTopology.physicalIndex();

        // 2. 让屏障前已完成的写入对 count/scroll 可见，再统计源文档
        esClient.indices().refresh(r -> r.index(oldPhysicalIndex));
        long totalDocs = esClient.count(c -> c.index(oldPhysicalIndex)).count();
        log.info("Rebuild: old index [{}] has {} documents to migrate", oldPhysicalIndex, totalDocs);

        // 3. 建新版本索引
        String newPhysicalIndex = physicalIndexFactory.newPhysicalIndexName();
        log.info("Rebuild: creating new index [{}] with dim={}",
                newPhysicalIndex, targetProfile.dimension());
        physicalIndexFactory.create(newPhysicalIndex, targetProfile);

        // 4. 存量数据迁移
        try {
            SegmentIndexMigrationRunner.MigrationResult migration =
                    migrationRunner.migrate(
                    oldPhysicalIndex,
                    newPhysicalIndex,
                    totalDocs,
                    targetProfile,
                    embeddingSession,
                    this::updateRebuildProgress);

            // 5. alias 原子切换到新索引
            log.info("Rebuild: switching alias from [{}] to [{}]",
                    oldPhysicalIndex, newPhysicalIndex);
            switchAliasesAndActivate(oldPhysicalIndex, newPhysicalIndex, targetProfile);

            stateRef.updateAndGet(current -> current.withRebuildProgress(
                    new SegmentIndexRebuildProgress(
                            migration.processedCount(), migration.sourceCount(), "COMPLETED")));

            // Keep the old physical index as a rollback snapshot. Cleanup must be explicit.
            log.info("Rebuild: old index [{}] retained for rollback; new index [{}] has {} documents",
                    oldPhysicalIndex, newPhysicalIndex, migration.targetCount());
        } catch (Exception e) {
            log.error("Rebuild: migration or alias switch failed for new index [{}]",
                    newPhysicalIndex, e);
            cleanupFailedTargetIndex(newPhysicalIndex, readAlias, writeAlias);
            throw e;
        }
    }

    private void updateRebuildProgress(
            SegmentIndexMigrationRunner.Progress progress
    ) {
        stateRef.updateAndGet(current -> current.withRebuildProgress(
                new SegmentIndexRebuildProgress(
                        progress.migrated(), progress.total(), progress.phase())));
    }

    void switchAliasesAndActivate(
            String oldPhysicalIndex,
            String newPhysicalIndex,
            EmbeddingProfile targetProfile
    ) throws Exception {
        aliasManager.switchAliases(oldPhysicalIndex, newPhysicalIndex);
        try {
            if (retrievalCapabilityAcl != null) {
                retrievalCapabilityAcl.activateServingProfile(targetProfile);
            }
        } catch (Exception activationFailure) {
            aliasManager.switchAliases(newPhysicalIndex, oldPhysicalIndex);
            throw new IllegalStateException(
                    "Embedding config activation failed after alias switch",
                    activationFailure);
        }
    }

    private void cleanupFailedTargetIndex(
            String newPhysicalIndex,
            String readAlias,
            String writeAlias
    ) {
        if (isReferencedByAlias(newPhysicalIndex, readAlias)
                || isReferencedByAlias(newPhysicalIndex, writeAlias)) {
            log.error("Rebuild: new index [{}] is referenced by an alias; refusing automatic deletion",
                    newPhysicalIndex);
            return;
        }
        try {
            esClient.indices().delete(d -> d.index(newPhysicalIndex));
            log.info("Rebuild: orphaned new index [{}] deleted", newPhysicalIndex);
        } catch (Exception cleanupException) {
            log.warn("Rebuild: failed to delete orphaned new index [{}]: {}",
                    newPhysicalIndex, cleanupException.getMessage());
        }
    }

    private boolean isReferencedByAlias(String physicalIndex, String alias) {
        try {
            return esClient.indices().existsAlias(e -> e
                    .index(physicalIndex)
                    .name(alias)).value();
        } catch (Exception e) {
            log.warn("Rebuild: cannot verify alias [{}] for index [{}], treating it as referenced: {}",
                    alias, physicalIndex, e.getMessage());
            return true;
        }
    }

    @Override
    public SegmentIndexStatusDTO status() {
        EmbeddingProfile activeProfile = embeddingProfileProvider.getActiveEmbeddingProfile()
                .orElse(null);
        return status(activeProfile);
    }

    private SegmentIndexStatusDTO status(EmbeddingProfile expectedProfile) {
        SegmentIndexLifecycleState current = stateRef.get();
        if (current.indexExists() != null) {
            if (shouldRefreshAliasTopology(current)) {
                SegmentIndexTopologyInspector.IndexInspection inspection =
                        topologyInspector.inspect(false, current.readIndex());
                current = mergeInspection(inspection);
            }
            current = clearObsoletePendingRebuild(current);
            return statusAssembler.assemble(
                    current, effectiveExpectedProfile(current, expectedProfile));
        }

        SegmentIndexTopologyInspector.IndexInspection inspection =
                topologyInspector.inspect(true, null);
        lastAliasTopologyRefreshMs.set(System.currentTimeMillis());
        SegmentIndexLifecycleState updated = mergeInspection(inspection);
        updated = clearObsoletePendingRebuild(updated);
        return statusAssembler.assemble(
                updated, effectiveExpectedProfile(updated, expectedProfile));
    }

    private EmbeddingProfile effectiveExpectedProfile(
            SegmentIndexLifecycleState state,
            EmbeddingProfile activeProfile
    ) {
        return state.pendingRebuild() == null
                ? activeProfile : state.pendingRebuild().targetProfile();
    }

    private SegmentIndexLifecycleState clearObsoletePendingRebuild(
            SegmentIndexLifecycleState observed
    ) {
        SegmentIndexLifecycleState current = observed;
        while (hasObsoletePendingRebuild(current)) {
            SegmentIndexPendingRebuild pending = current.pendingRebuild();
            SegmentIndexLifecycleState updated = current.withoutPendingRebuild(
                    preserveAliasTopologyError(current.lastError()));
            if (stateRef.compareAndSet(current, updated)) {
                log.info("Pending rebuild task cleared: taskId={}, targetFingerprint={}, expectedFingerprint={}, reason={}",
                        pending.taskId(),
                        pending.targetProfile().fingerprint(),
                        current.actualProfileFingerprint(),
                        pending.reason());
                return updated;
            }
            current = stateRef.get();
        }
        return current;
    }

    private boolean hasObsoletePendingRebuild(
            SegmentIndexLifecycleState state
    ) {
        return state.status() == SegmentIndexStatus.READY
                && state.pendingRebuild() != null
                && !pendingRebuildStillNeeded(state);
    }

    private boolean pendingRebuildStillNeeded(
            SegmentIndexLifecycleState state
    ) {
        SegmentIndexPendingRebuild pending = state.pendingRebuild();
        if (pending == null) {
            return false;
        }
        EmbeddingProfile targetProfile = pending.targetProfile();
        return !Objects.equals(state.actualDim(), targetProfile.dimension())
                || !Objects.equals(
                state.actualProfileFingerprint(),
                targetProfile.fingerprint());
    }

    private String preserveAliasTopologyError(String lastError) {
        return lastError != null && lastError.startsWith(ALIAS_TOPOLOGY_ERROR_PREFIX)
                ? lastError
                : null;
    }

    private boolean shouldRefreshAliasTopology(
            SegmentIndexLifecycleState current
    ) {
        if (current.status() != SegmentIndexStatus.READY) {
            return false;
        }
        long now = System.currentTimeMillis();
        long previous = lastAliasTopologyRefreshMs.get();
        if (now - previous < ALIAS_TOPOLOGY_REFRESH_INTERVAL_MS) {
            return false;
        }
        return lastAliasTopologyRefreshMs.compareAndSet(previous, now);
    }

    private SegmentIndexLifecycleState mergeInspection(
            SegmentIndexTopologyInspector.IndexInspection inspection
    ) {
        return stateRef.updateAndGet(previous -> {
            SegmentIndexLifecycleState base = previous;
            if (inspection.readable() && previous.status() == SegmentIndexStatus.NOT_READY) {
                base = previous.withStatus(SegmentIndexStatus.READY, null);
            }
            SegmentIndexTopologyInspector.MappingProfile mappingProfile =
                    inspection.mappingProfile();
            SegmentIndexLifecycleState updated = base.withIndexInfo(
                    inspection.readable(),
                    inspection.readable() ? inspection.readIndex() : null,
                    inspection.readable(),
                    inspection.writable(),
                    mappingProfile.loaded() ? mappingProfile.actualDim() : previous.actualDim(),
                    mappingProfile.loaded() ? mappingProfile.actualModel() : previous.actualModel(),
                    mappingProfile.loaded()
                            ? mappingProfile.actualProfileFingerprint()
                            : previous.actualProfileFingerprint());
            return updated.withLastError(resolveInspectionError(previous.lastError(), inspection));
        });
    }

    private String resolveInspectionError(
            String previousError,
            SegmentIndexTopologyInspector.IndexInspection inspection
    ) {
        if (!inspection.readable() || !inspection.writable()) {
            return ALIAS_TOPOLOGY_ERROR_PREFIX
                    + (StringUtils.hasText(inspection.error()) ? inspection.error() : "unknown");
        }
        if (previousError != null && previousError.startsWith(ALIAS_TOPOLOGY_ERROR_PREFIX)) {
            return null;
        }
        return previousError;
    }

    @Override
    public boolean retryCreate() {
        SegmentIndexLifecycleState current = stateRef.get();
        if (current.status() != SegmentIndexStatus.NOT_READY) {
            log.warn("Retry create: status is {}, not NOT_READY", current.status());
            return false;
        }
        return tryScheduleCreate();
    }

    @Override
    public String prepareRebuild() {
        SegmentIndexPendingRebuild requested = stateRef.get().pendingRebuild();
        if (requested != null) {
            return requested.taskId();
        }
        EmbeddingProfile expectedProfile = embeddingProfileProvider.getActiveEmbeddingProfile()
                .orElse(null);
        if (expectedProfile == null) {
            log.warn("Prepare rebuild: no active embedding profile");
            return null;
        }
        try {
            aliasManager.requireValid();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("索引 alias 不合法，无法重建：" + e.getMessage(), e);
        }
        SegmentIndexStatusDTO s = status(expectedProfile);
        if (!s.isIndexExists() || !s.isReadable() || !s.isWritable() || s.getActualDim() == null) {
            log.warn("Prepare rebuild: index not ready, indexExists={}, actualDim={}, expectedDim={}",
                    s.isIndexExists(),
                    s.getActualDim(),
                    expectedProfile.dimension());
            return null;
        }
        if (s.getActualDim().equals(expectedProfile.dimension())) {
            if (Objects.equals(
                    s.getActualProfileFingerprint(), expectedProfile.fingerprint())) {
                clearObsoletePendingRebuild(stateRef.get());
                log.info("Prepare rebuild: dimensions and model match, no rebuild needed");
                return null;
            }
            log.info("Prepare rebuild: dimensions match but model changed ({} -> {}), triggering rebuild",
                    s.getActualModel(), s.getExpectedModel());
        }
        String reason = statusAssembler.buildRebuildReason(s);
        return createPendingRebuildTask(reason, expectedProfile);
    }
}
