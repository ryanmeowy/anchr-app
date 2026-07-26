package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.ClearScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.integration.ai.adapter.ServingEmbeddingConfigActivator;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentIndexManagerImpl implements SegmentIndexManager {

    private static final String SETTINGS_PATH = "es-settings.json";
    private static final String MAPPING_PATH = "es-kb-segment-mapping.json";
    private static final int SCROLL_BATCH_SIZE = 50;
    private static final int SCROLL_KEEP_ALIVE_MINUTES = 5;
    private static final int EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS = 5;
    private static final long EMBEDDING_RATE_LIMIT_BACKOFF_MS = 5_000L;
    private static final long EMBEDDING_CALL_INTERVAL_MS = 500L;
    private static final String META_PROFILE_VERSION = "embeddingProfileVersion";
    private static final String META_PROFILE_FINGERPRINT = "embeddingProfileFingerprint";
    private static final String META_CAPABILITY = "embeddingCapability";
    private static final String META_MODEL = "embeddingModel";
    private static final String META_DIMENSION = "embeddingDimension";
    private static final long ALIAS_TOPOLOGY_REFRESH_INTERVAL_MS = 15_000L;
    private static final String ALIAS_TOPOLOGY_ERROR_PREFIX = "Alias topology invalid: ";

    private final ElasticsearchClient esClient;
    private final SegmentIndexConfig kbSegmentConfig;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final SearchEmbeddingPort embeddingPort;
    private final SearchObjectStoragePort storagePort;

    @Qualifier("indexInitExecutor")
    private final Executor indexInitExecutor;
    private final SegmentIndexWriteBarrier indexWriteBarrier;
    private final SegmentIndexAliasManager aliasManager;
    private ServingEmbeddingConfigActivator servingConfigActivator;

    @Autowired(required = false)
    void setServingConfigActivator(ServingEmbeddingConfigActivator activator) {
        this.servingConfigActivator = activator;
    }

    // Instance-level lock; use a distributed lock for multi-instance deployments.
    private final ReentrantLock indexOpLock = new ReentrantLock();

    // Instance-local lifecycle state; persist or externalize it before running multiple app instances.
    private final AtomicReference<SegmentIndexState> stateRef =
            new AtomicReference<>(SegmentIndexState.initial());
    private final AtomicLong lastAliasTopologyRefreshMs = new AtomicLong(0);

    private record SegmentIndexState(
            SegmentIndexStatus status,
            String lastError,
            PendingRebuildState pendingRebuild,
            RebuildProgressState rebuildProgress,
            Boolean indexExists,
            String readIndex,
            boolean readable,
            boolean writable,
            Integer actualDim,
            String actualModel,
            String actualProfileFingerprint
    ) {
        private static SegmentIndexState initial() {
            return new SegmentIndexState(
                    SegmentIndexStatus.NOT_READY, null, null, null,
                    null, null, false, false, null, null, null);
        }

        private SegmentIndexState withStatus(SegmentIndexStatus newStatus, String newLastError) {
            return new SegmentIndexState(
                    newStatus, newLastError, pendingRebuild, rebuildProgress,
                    indexExists, readIndex, readable, writable,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState withPendingRebuild(PendingRebuildState newPendingRebuild) {
            return new SegmentIndexState(
                    status, lastError, newPendingRebuild, rebuildProgress,
                    indexExists, readIndex, readable, writable,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState withoutPendingRebuild(String newLastError) {
            return new SegmentIndexState(
                    status, newLastError, null, null,
                    indexExists, readIndex, readable, writable,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState withRebuildProgress(RebuildProgressState newRebuildProgress) {
            return new SegmentIndexState(
                    status, lastError, pendingRebuild, newRebuildProgress,
                    indexExists, readIndex, readable, writable,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState withLastError(String newLastError) {
            return new SegmentIndexState(
                    status, newLastError, pendingRebuild, rebuildProgress,
                    indexExists, readIndex, readable, writable,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState withIndexInfo(
                boolean newIndexExists,
                String newReadIndex,
                boolean newReadable,
                boolean newWritable,
                Integer newActualDim,
                String newActualModel,
                String newActualProfileFingerprint
        ) {
            return new SegmentIndexState(
                    status, lastError, pendingRebuild, rebuildProgress,
                    newIndexExists,
                    newReadIndex,
                    newReadable,
                    status == SegmentIndexStatus.READY && newWritable,
                    newActualDim,
                    newActualModel,
                    newActualProfileFingerprint);
        }

        private SegmentIndexState claimRebuild() {
            return new SegmentIndexState(
                    SegmentIndexStatus.REBUILDING, null, pendingRebuild,
                    new RebuildProgressState(0, 0, "PREPARING"),
                    indexExists, readIndex, readable, false,
                    actualDim, actualModel, actualProfileFingerprint);
        }

        private SegmentIndexState createSucceeded(EmbeddingProfile profile) {
            return new SegmentIndexState(
                    SegmentIndexStatus.READY, null, null, null,
                    true, null, true, true,
                    profile.dimension(), profile.modelName(), profile.fingerprint());
        }

        private SegmentIndexState rebuildSucceeded(EmbeddingProfile profile) {
            return new SegmentIndexState(
                    SegmentIndexStatus.READY, null, null, rebuildProgress,
                    true, null, true, true,
                    profile.dimension(), profile.modelName(), profile.fingerprint());
        }

        private SegmentIndexState rebuildFailed(
                String error,
                boolean aliasReadable,
                boolean aliasWritable
        ) {
            RebuildProgressState failedProgress = rebuildProgress == null
                    ? new RebuildProgressState(0, 0, "FAILED")
                    : rebuildProgress.withPhase("FAILED");
            return new SegmentIndexState(
                    SegmentIndexStatus.READY, error, pendingRebuild, failedProgress,
                    indexExists, readIndex, aliasReadable, aliasWritable,
                    actualDim, actualModel, actualProfileFingerprint);
        }
    }

    private record PendingRebuildState(
            String taskId,
            EmbeddingProfile targetProfile,
            String reason,
            String createdAt
    ) {
        private SegmentIndexStatusDTO.PendingRebuild toDto() {
            return SegmentIndexStatusDTO.PendingRebuild.builder()
                    .taskId(taskId)
                    .expectedDim(targetProfile.dimension())
                    .reason(reason)
                    .createdAt(createdAt)
                    .build();
        }
    }

    private record RebuildProgressState(long migrated, long total, String phase) {
        private RebuildProgressState withPhase(String newPhase) {
            return new RebuildProgressState(migrated, total, newPhase);
        }

        private SegmentIndexStatusDTO.RebuildProgress toDto() {
            return SegmentIndexStatusDTO.RebuildProgress.builder()
                    .migrated(migrated)
                    .total(total)
                    .phase(phase)
                    .build();
        }
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
        SegmentIndexState updated = stateRef.updateAndGet(current -> {
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
            SegmentIndexState current = stateRef.get();
            if (current.status() != SegmentIndexStatus.NOT_READY) {
                return false;
            }
            SegmentIndexState claimed = current.withStatus(SegmentIndexStatus.INITIALIZING, null);
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
        String physicalIndexName = newPhysicalIndexName();
        log.info("Create: building index [{}] with dim={}",
                physicalIndexName, profile.dimension());
        createPhysicalIndex(physicalIndexName, profile);
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
        PendingRebuildState pending = new PendingRebuildState(
                taskId,
                targetProfile,
                reason,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        while (true) {
            SegmentIndexState current = stateRef.get();
            if (current.status() != SegmentIndexStatus.READY) {
                throw new IllegalStateException(
                        "Cannot create rebuild task while index status is " + current.status());
            }
            PendingRebuildState existing = current.pendingRebuild();
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
        SegmentIndexState current = stateRef.get();
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
            SegmentIndexState current = stateRef.get();
            PendingRebuildState pending = current.pendingRebuild();
            if (current.status() != SegmentIndexStatus.READY
                    || pending == null
                    || !pending.taskId().equals(taskId)) {
                return null;
            }
            if (!pendingRebuildStillNeeded(current)) {
                clearObsoletePendingRebuild(current);
                return null;
            }
            SegmentIndexState claimed = current.claimRebuild();
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

    private boolean isActiveRebuild(SegmentIndexState state, RebuildClaim claim) {
        return state.status() == SegmentIndexStatus.REBUILDING
                && state.pendingRebuild() != null
                && state.pendingRebuild().taskId().equals(claim.taskId());
    }

    private record RebuildClaim(String taskId, EmbeddingProfile targetProfile) {
    }

    private record IndexInspection(
            boolean readable,
            boolean writable,
            String readIndex,
            String error,
            MappingProfile mappingProfile
    ) {
    }

    private record MappingProfile(
            boolean loaded,
            Integer actualDim,
            String actualModel,
            String actualProfileFingerprint
    ) {
        private static MappingProfile notLoaded() {
            return new MappingProfile(false, null, null, null);
        }

        private static MappingProfile empty() {
            return new MappingProfile(true, null, null, null);
        }
    }

    private record MigrationDocument(String id, SegmentDocument document) {
    }

    private record MigrationResult(long sourceCount, long processedCount, long targetCount) {
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
        String newPhysicalIndex = newPhysicalIndexName();
        log.info("Rebuild: creating new index [{}] with dim={}",
                newPhysicalIndex, targetProfile.dimension());
        createPhysicalIndex(newPhysicalIndex, targetProfile);

        // 4. 存量数据迁移
        try {
            MigrationResult migration = migrateData(
                    oldPhysicalIndex,
                    newPhysicalIndex,
                    totalDocs,
                    targetProfile,
                    embeddingSession);

            // 5. alias 原子切换到新索引
            log.info("Rebuild: switching alias from [{}] to [{}]",
                    oldPhysicalIndex, newPhysicalIndex);
            aliasManager.switchAliases(oldPhysicalIndex, newPhysicalIndex);
            try {
                if (servingConfigActivator != null) {
                    servingConfigActivator.activate(targetProfile);
                }
            } catch (Exception activationFailure) {
                aliasManager.switchAliases(newPhysicalIndex, oldPhysicalIndex);
                throw new IllegalStateException(
                        "Embedding config activation failed after alias switch",
                        activationFailure);
            }

            stateRef.updateAndGet(current -> current.withRebuildProgress(
                    new RebuildProgressState(
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

    private MigrationResult migrateData(
            String oldIndex,
            String newIndex,
            long totalDocs,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession
    ) throws Exception {
        long processed = 0;
        long projected = 0;
        SegmentRebuildProjectionPlanner projectionPlanner =
                new SegmentRebuildProjectionPlanner(targetProfile.capability());
        stateRef.updateAndGet(current -> current.withRebuildProgress(
                new RebuildProgressState(0, totalDocs, "MIGRATING")));

        String scrollId = null;
        try {
            SearchResponse<SegmentDocument> searchResponse = esClient.search(
                    SearchRequest.of(s -> s
                            .index(oldIndex)
                            .size(SCROLL_BATCH_SIZE)
                            .sort(sort -> sort.field(field -> field
                                    .field("assetId").order(SortOrder.Asc)))
                            .sort(sort -> sort.field(field -> field
                                    .field("indexGeneration").order(SortOrder.Asc)
                                    .missing("_first")))
                            .sort(sort -> sort.field(field -> field
                                    .field("segmentType").order(SortOrder.Desc)
                                    .missing("_last")))
                            .sort(sort -> sort.field(field -> field
                                    .field("segmentId").order(SortOrder.Asc)))
                            .scroll(t -> t.time(SCROLL_KEEP_ALIVE_MINUTES + "m"))),
                    SegmentDocument.class);

            scrollId = searchResponse.scrollId();
            List<Hit<SegmentDocument>> hits = searchResponse.hits().hits();

            while (!hits.isEmpty()) {
                List<MigrationDocument> batch = prepareMigrationBatch(
                        hits, targetProfile, embeddingSession, projectionPlanner);
                writeMigrationBatch(newIndex, batch);

                processed += hits.size();
                projected += batch.size();
                long processedCount = processed;
                stateRef.updateAndGet(current -> current.withRebuildProgress(
                        new RebuildProgressState(processedCount, totalDocs, "MIGRATING")));
                log.info("Rebuild: processed {}/{} source documents, projected {} target documents",
                        processed, totalDocs, projected);

                String currentScrollId = scrollId;
                ScrollResponse<SegmentDocument> scrollResponse = esClient.scroll(
                        ScrollRequest.of(s -> s.scrollId(currentScrollId)
                                .scroll(t -> t.time(SCROLL_KEEP_ALIVE_MINUTES + "m"))),
                        SegmentDocument.class);
                scrollId = scrollResponse.scrollId();
                hits = scrollResponse.hits().hits();
            }
        } finally {
            clearScrollQuietly(scrollId);
        }

        esClient.indices().refresh(r -> r.index(newIndex));
        long targetDocs = esClient.count(c -> c.index(newIndex)).count();
        validateMigrationCounts(totalDocs, processed, projected, targetDocs);

        long processedCount = processed;
        stateRef.updateAndGet(current -> current.withRebuildProgress(
                new RebuildProgressState(processedCount, totalDocs, "SWITCHING_ALIAS")));
        log.info("Rebuild: data migration validated, source={}, projected={}, target={}",
                totalDocs, projected, targetDocs);
        return new MigrationResult(totalDocs, processed, targetDocs);
    }

    private List<MigrationDocument> prepareMigrationBatch(
            List<Hit<SegmentDocument>> hits,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            SegmentRebuildProjectionPlanner projectionPlanner
    ) {
        List<MigrationDocument> batch = new ArrayList<>(hits.size());
        for (Hit<SegmentDocument> hit : hits) {
            if (hit == null || hit.source() == null) {
                throw new IllegalStateException("Rebuild source contains a document without _source");
            }
            SegmentDocument document = hit.source();
            String documentId = StringUtils.hasText(document.getSegmentId())
                    ? document.getSegmentId()
                    : hit.id();
            if (!StringUtils.hasText(documentId)) {
                throw new IllegalStateException("Rebuild source contains a document without an id");
            }
            if (!StringUtils.hasText(document.getSegmentId())) {
                document.setSegmentId(documentId);
            }

            List<SegmentRebuildProjectionPlanner.PlannedDocument> planned =
                    projectionPlanner.plan(documentId, document);
            for (SegmentRebuildProjectionPlanner.PlannedDocument target : planned) {
                EmbeddingProjection projection = target.projection();
                if (projection != null) {
                    sleepUninterruptibly(EMBEDDING_CALL_INTERVAL_MS);
                    String source = projection.inputType()
                            == EmbeddingProjection.InputType.IMAGE
                            ? resolveRebuildImageInput(projection.source())
                            : projection.source();
                    List<Float> embedding = callEmbeddingWithRetry(
                            () -> embeddingSession.embed(
                                    source, projection.inputType().requestValue()),
                            target.id(),
                            projection.inputType().requestValue());
                    validateEmbedding(
                            target.id(), embedding, targetProfile.dimension());
                    target.document().setEmbedding(embedding);
                }
                batch.add(new MigrationDocument(
                        target.id(), target.document()));
            }
        }
        return batch;
    }

    private void writeMigrationBatch(String newIndex, List<MigrationDocument> batch) throws Exception {
        if (batch.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (MigrationDocument migrationDocument : batch) {
            bulkBuilder.operations(op -> op
                    .index(index -> index
                            .index(newIndex)
                            .id(migrationDocument.id())
                            .document(migrationDocument.document())));
        }

        var bulkResponse = esClient.bulk(bulkBuilder.build());
        if (bulkResponse.items().size() != batch.size()) {
            throw new IllegalStateException(
                    "Rebuild bulk response size mismatch: expected " + batch.size()
                            + ", actual " + bulkResponse.items().size());
        }

        List<String> failures = bulkResponse.items().stream()
                .filter(item -> item.error() != null)
                .map(this::formatBulkFailure)
                .toList();
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Rebuild bulk write failed for " + failures.size() + " document(s): "
                            + String.join("; ", failures.stream().limit(5).toList()));
        }
    }

    private String formatBulkFailure(BulkResponseItem item) {
        return item.id() + "=" + item.error().reason();
    }

    String resolveRebuildImageInput(String stableSource) {
        if (!StringUtils.hasText(stableSource)) {
            throw new IllegalStateException(
                    "Rebuild IMAGE_VISUAL has no stable original image source.");
        }
        String normalized = stableSource.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return normalized;
        }
        return storagePort.buildAiImageInput(
                normalized, SearchObjectStoragePort.AiInputValidity.SHORT);
    }

    static void validateEmbedding(String documentId, List<Float> embedding, int expectedDim) {
        if (embedding == null || embedding.size() != expectedDim) {
            int actualDim = embedding == null ? 0 : embedding.size();
            throw new IllegalStateException(
                    "Rebuild embedding dimension mismatch for document " + documentId
                            + ": expected " + expectedDim + ", actual " + actualDim);
        }
        boolean invalidValue = embedding.stream()
                .anyMatch(value -> value == null || !Float.isFinite(value));
        if (invalidValue) {
            throw new IllegalStateException(
                    "Rebuild embedding contains non-finite values for document " + documentId);
        }
    }

    private List<Float> callEmbeddingWithRetry(
            Supplier<List<Float>> call, String documentId, String sourceType) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (!isRateLimitError(e) || attempt >= EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS) {
                    throw e;
                }
                long waitMs = resolveEmbeddingBackoffMs(attempt);
                log.warn("Rebuild embedding rate limited, documentId={}, sourceType={}, attempt={}/{}, waitMs={}",
                        documentId, sourceType, attempt, EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS, waitMs);
                sleepUninterruptibly(waitMs);
            }
        }
        throw lastError == null
                ? new IllegalStateException("Rebuild embedding failed for document " + documentId)
                : lastError;
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("429")
                    || message.contains("Throttling")
                    || message.contains("RateQuota")
                    || message.toLowerCase().contains("rate limit"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long resolveEmbeddingBackoffMs(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 4);
        return EMBEDDING_RATE_LIMIT_BACKOFF_MS * multiplier;
    }

    private void sleepUninterruptibly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void validateMigrationCounts(
            long sourceDocs,
            long processedSourceDocs,
            long projectedTargetDocs,
            long actualTargetDocs
    ) {
        if (sourceDocs != processedSourceDocs
                || projectedTargetDocs != actualTargetDocs) {
            throw new IllegalStateException(
                    "Rebuild document count mismatch: source=" + sourceDocs
                            + ", processed=" + processedSourceDocs
                            + ", projected=" + projectedTargetDocs
                            + ", target=" + actualTargetDocs);
        }
    }

    private void clearScrollQuietly(String scrollId) {
        if (!StringUtils.hasText(scrollId)) {
            return;
        }
        try {
            esClient.clearScroll(ClearScrollRequest.of(c -> c.scrollId(scrollId)));
        } catch (Exception e) {
            log.warn("Rebuild: failed to clear scroll context: {}", e.getMessage());
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
        SegmentIndexState current = stateRef.get();
        if (current.indexExists() != null) {
            if (shouldRefreshAliasTopology(current)) {
                IndexInspection inspection = inspectIndex(false, current.readIndex());
                current = mergeInspection(inspection);
            }
            current = clearObsoletePendingRebuild(current);
            return toStatusDto(current, effectiveExpectedProfile(current, expectedProfile));
        }

        IndexInspection inspection = inspectIndex(true, null);
        lastAliasTopologyRefreshMs.set(System.currentTimeMillis());
        SegmentIndexState updated = mergeInspection(inspection);
        updated = clearObsoletePendingRebuild(updated);
        return toStatusDto(updated, effectiveExpectedProfile(updated, expectedProfile));
    }

    private EmbeddingProfile effectiveExpectedProfile(
            SegmentIndexState state, EmbeddingProfile activeProfile) {
        return state.pendingRebuild() == null
                ? activeProfile : state.pendingRebuild().targetProfile();
    }

    private SegmentIndexState clearObsoletePendingRebuild(SegmentIndexState observed) {
        SegmentIndexState current = observed;
        while (hasObsoletePendingRebuild(current)) {
            PendingRebuildState pending = current.pendingRebuild();
            SegmentIndexState updated = current.withoutPendingRebuild(
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

    private boolean hasObsoletePendingRebuild(SegmentIndexState state) {
        return state.status() == SegmentIndexStatus.READY
                && state.pendingRebuild() != null
                && !pendingRebuildStillNeeded(state);
    }

    private boolean pendingRebuildStillNeeded(SegmentIndexState state) {
        PendingRebuildState pending = state.pendingRebuild();
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

    private boolean shouldRefreshAliasTopology(SegmentIndexState current) {
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

    private IndexInspection inspectIndex(boolean forceMappingLoad, String currentReadIndex) {
        AliasTopology topology = aliasManager.inspect();
        boolean loadMapping = topology.readable()
                && (forceMappingLoad || !Objects.equals(topology.readIndex(), currentReadIndex));
        MappingProfile mappingProfile = loadMapping
                ? inspectMappingProfile(topology.readIndex())
                : MappingProfile.notLoaded();
        return new IndexInspection(
                topology.readable(),
                topology.writable(),
                topology.readIndex(),
                topology.error(),
                mappingProfile);
    }

    private SegmentIndexState mergeInspection(IndexInspection inspection) {
        return stateRef.updateAndGet(previous -> {
            SegmentIndexState base = previous;
            if (inspection.readable() && previous.status() == SegmentIndexStatus.NOT_READY) {
                base = previous.withStatus(SegmentIndexStatus.READY, null);
            }
            MappingProfile mappingProfile = inspection.mappingProfile();
            SegmentIndexState updated = base.withIndexInfo(
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

    private String resolveInspectionError(String previousError, IndexInspection inspection) {
        if (!inspection.readable() || !inspection.writable()) {
            return ALIAS_TOPOLOGY_ERROR_PREFIX
                    + (StringUtils.hasText(inspection.error()) ? inspection.error() : "unknown");
        }
        if (previousError != null && previousError.startsWith(ALIAS_TOPOLOGY_ERROR_PREFIX)) {
            return null;
        }
        return previousError;
    }

    private MappingProfile inspectMappingProfile(String indexName) {
        if (!StringUtils.hasText(indexName)) {
            return MappingProfile.empty();
        }
        try {
            Map<String, IndexMappingRecord> mappings = esClient.indices()
                    .getMapping(m -> m.index(indexName)).result();
            return mappings.values().stream()
                    .findFirst()
                    .map(record -> toMappingProfile(indexName, record))
                    .orElseGet(MappingProfile::empty);
        } catch (Exception e) {
            log.warn("Failed to query index status via alias [{}]: {}", indexName, e.getMessage());
            return MappingProfile.empty();
        }
    }

    private MappingProfile toMappingProfile(String indexName, IndexMappingRecord record) {
        if (record.mappings() == null) {
            return MappingProfile.empty();
        }
        Integer actualDim = null;
        var embeddingProp = record.mappings().properties().get("embedding");
        if (embeddingProp != null && embeddingProp.isDenseVector()) {
            actualDim = embeddingProp.denseVector().dims();
        }

        Map<String, JsonData> metadata = record.mappings().meta();
        String actualModel = readMetadataString(metadata, META_MODEL);
        String actualProfileFingerprint = readMetadataString(metadata, META_PROFILE_FINGERPRINT);
        Integer metadataVersion = readMetadataInteger(metadata, META_PROFILE_VERSION);
        Integer metadataDimension = readMetadataInteger(metadata, META_DIMENSION);
        if (!Objects.equals(metadataVersion, 1)
                || !Objects.equals(metadataDimension, actualDim)) {
            log.warn("Index [{}] has invalid embedding profile metadata", indexName);
            actualProfileFingerprint = null;
        }
        return new MappingProfile(true, actualDim, actualModel, actualProfileFingerprint);
    }

    private SegmentIndexStatusDTO toStatusDto(
            SegmentIndexState state,
            EmbeddingProfile expectedProfile
    ) {
        return SegmentIndexStatusDTO.builder()
                .status(state.status())
                .indexExists(Boolean.TRUE.equals(state.indexExists()))
                .readable(state.readable())
                .writable(state.writable())
                .actualDim(state.actualDim())
                .actualModel(state.actualModel())
                .actualProfileFingerprint(state.actualProfileFingerprint())
                .expectedDim(expectedProfile == null ? null : expectedProfile.dimension())
                .expectedModel(expectedProfile == null ? null : expectedProfile.modelName())
                .expectedProfileFingerprint(
                        expectedProfile == null ? null : expectedProfile.fingerprint())
                .pendingRebuild(toPendingRebuildDto(state.pendingRebuild()))
                .rebuildProgress(toRebuildProgressDto(state.rebuildProgress()))
                .lastError(state.lastError())
                .build();
    }

    static String readMetadataString(Map<String, JsonData> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return metadata.get(key).to(String.class);
    }

    static Integer readMetadataInteger(Map<String, JsonData> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return metadata.get(key).to(Integer.class);
    }

    private SegmentIndexStatusDTO.PendingRebuild toPendingRebuildDto(PendingRebuildState pending) {
        return pending == null ? null : pending.toDto();
    }

    private SegmentIndexStatusDTO.RebuildProgress toRebuildProgressDto(RebuildProgressState progress) {
        return progress == null ? null : progress.toDto();
    }

    @Override
    public boolean retryCreate() {
        SegmentIndexState current = stateRef.get();
        if (current.status() != SegmentIndexStatus.NOT_READY) {
            log.warn("Retry create: status is {}, not NOT_READY", current.status());
            return false;
        }
        return tryScheduleCreate();
    }

    private String loadAndProcessMapping(int dims) throws Exception {
        ClassPathResource resource = new ClassPathResource(MAPPING_PATH);
        try (InputStream is = resource.getInputStream()) {
            String json = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            return json.replace("\"@DIMS@\"", String.valueOf(dims));
        }
    }

    private String newPhysicalIndexName() {
        return kbSegmentConfig.getIndexName() + "_" + System.currentTimeMillis();
    }

    private void createPhysicalIndex(String physicalIndexName, EmbeddingProfile profile)
            throws Exception {
        String mappingJson = loadAndProcessMapping(profile.dimension());
        try (InputStream is = new ClassPathResource(SETTINGS_PATH).getInputStream()){
            Map<String, JsonData> profileMetadata = toMappingMetadata(profile);
            esClient.indices().create(c -> c
                    .index(physicalIndexName)
                    .settings(IndexSettings.of(s -> s.withJson(is)))
                    .mappings(TypeMapping.of(m -> m
                            .withJson(new StringReader(mappingJson))
                            .meta(profileMetadata)))
            );
        }
        log.info("Index [{}] created with embedding profile {}, dim={}, model={}",
                physicalIndexName,
                profile.fingerprint(),
                profile.dimension(),
                profile.modelName());
    }

    static Map<String, JsonData> toMappingMetadata(EmbeddingProfile profile) {
        return Map.of(
                META_PROFILE_VERSION, JsonData.of(1),
                META_PROFILE_FINGERPRINT, JsonData.of(profile.fingerprint()),
                META_CAPABILITY, JsonData.of(profile.capability()),
                META_MODEL, JsonData.of(profile.modelName()),
                META_DIMENSION, JsonData.of(profile.dimension()));
    }

    @Override
    public String prepareRebuild() {
        PendingRebuildState requested = stateRef.get().pendingRebuild();
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
        String reason = buildRebuildReason(s);
        return createPendingRebuildTask(reason, expectedProfile);
    }

    private String buildRebuildReason(SegmentIndexStatusDTO s) {
        boolean dimChanged = !Objects.equals(s.getActualDim(), s.getExpectedDim());
        boolean profileChanged = !Objects.equals(
                s.getActualProfileFingerprint(), s.getExpectedProfileFingerprint());
        boolean modelNameChanged = !Objects.equals(s.getActualModel(), s.getExpectedModel());
        if (dimChanged && profileChanged) {
            String modelText = modelNameChanged
                    ? "，模型 " + s.getActualModel() + " -> " + s.getExpectedModel()
                    : "，模型 " + s.getExpectedModel();
            return "Embedding 配置已变化：维度 " + s.getActualDim() + " -> " + s.getExpectedDim()
                    + modelText;
        }
        if (dimChanged) {
            return "Embedding 维度已变化：" + s.getActualDim() + " -> " + s.getExpectedDim();
        }
        if (modelNameChanged) {
            return "Embedding 模型已变化：" + s.getActualModel() + " -> " + s.getExpectedModel();
        }
        return "Embedding 配置已变化：模型 " + s.getExpectedModel();
    }
}
