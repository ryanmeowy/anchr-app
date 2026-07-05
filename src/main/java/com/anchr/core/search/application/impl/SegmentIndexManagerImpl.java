package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.domain.port.IndexDimensionProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO.PendingRebuild;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO.RebuildProgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentIndexManagerImpl implements SegmentIndexManager {

    private static final String SETTINGS_PATH = "es-settings.json";
    private static final String MAPPING_PATH = "es-kb-segment-mapping.json";
    private static final int SCROLL_BATCH_SIZE = 500;
    private static final int SCROLL_KEEP_ALIVE_MINUTES = 5;

    private final ElasticsearchClient esClient;
    private final SegmentIndexConfig kbSegmentConfig;
    private final IndexDimensionProvider dimensionProvider;
    private final SearchEmbeddingPort embeddingPort;
    private final SearchObjectStoragePort storagePort;

    @Qualifier("indexInitExecutor")
    private final Executor indexInitExecutor;

    private final ReentrantLock indexOpLock = new ReentrantLock();

   private volatile String indexStatus = "NOT_READY";
   private volatile String lastError;
   private volatile PendingRebuild pendingRebuild;
   private volatile RebuildProgress rebuildProgress;
   private volatile Boolean cachedIndexExists;
   private volatile Integer cachedActualDim;
   private volatile String cachedActualModel;

    // ==================== Boot ====================

   @EventListener(ApplicationReadyEvent.class)
   public void onReady() {
       SegmentIndexStatusDTO s = status();
       if (!s.isIndexExists()) {
           dimensionProvider.getActiveEmbeddingDimension().ifPresentOrElse(
                   dim -> {
                       log.info("Boot: index missing, active embedding dim={}, triggering async create", dim);
                       asyncCreate();
                   },
                   () -> log.info("Boot: index missing but no active embedding configured, skipping")
           );
       } else {
           indexStatus = "READY";
           log.info("Boot: index exists via alias [{}], actualDim={}, expectedDim={}",
                   kbSegmentConfig.getReadTargetName(), s.getActualDim(), s.getExpectedDim());
       }
   }

    // ==================== 1a: async create ====================

    @Override
    public void asyncCreate() {
        indexInitExecutor.execute(() -> {
            if (!indexOpLock.tryLock()) {
                log.info("Create already in progress, skipping");
                return;
            }
            try {
                indexStatus = "INITIALIZING";
                lastError = null;
                int dim = dimensionProvider.getActiveEmbeddingDimension()
                        .orElseThrow(() -> new IllegalStateException("No active embedding dimension"));
                doCreate(dim);
                indexStatus = "READY";
                cachedIndexExists = true;
                cachedActualDim = dim;
                cachedActualModel = dimensionProvider.getActiveEmbeddingModelKey().orElse(null);
                log.info("Index create completed, status=READY");
            } catch (Exception e) {
                indexStatus = "NOT_READY";
                lastError = e.getMessage();
                log.error("Index create failed: {}", e.getMessage(), e);
            } finally {
                indexOpLock.unlock();
            }
        });
    }

    private void doCreate(int dim) throws Exception {
        String physicalIndexName = newPhysicalIndexName();
        log.info("Create: building index [{}] with dim={}", physicalIndexName, dim);
        createPhysicalIndex(physicalIndexName, dim);
        ensureAliases(physicalIndexName);
    }

    // ==================== 1b + 1e: rebuild ====================

    @Override
    public void createPendingRebuild(String reason, int expectedDim) {
        String readAlias = kbSegmentConfig.getReadAlias();
        String writeAlias = kbSegmentConfig.getWriteAlias();
        if (!StringUtils.hasText(readAlias) || !StringUtils.hasText(writeAlias)) {
            throw new IllegalStateException(
                    "Read/write aliases must be configured for rebuild. " +
                    "Please set app.segment.read-alias and app.segment.write-alias.");
        }

        String taskId = UUID.randomUUID().toString();
        this.pendingRebuild = PendingRebuild.builder()
                .taskId(taskId)
                .expectedDim(expectedDim)
                .reason(reason)
                .createdAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
        log.info("Pending rebuild task created: taskId={}, expectedDim={}, reason={}", taskId, expectedDim, reason);
    }

    @Override
    public boolean confirmRebuild(String taskId) {
        if (pendingRebuild == null || !pendingRebuild.getTaskId().equals(taskId)) {
            log.warn("Rebuild confirm: task not found or mismatched, taskId={}", taskId);
            return false;
        }
       if (!indexOpLock.tryLock()) {
           log.warn("Rebuild confirm: rebuild or create already in progress, taskId={}", taskId);
           return false;
       }
        int dim = pendingRebuild.getExpectedDim();
        indexInitExecutor.execute(() -> {
            try {
                indexStatus = "REBUILDING";
                lastError = null;
                doRebuild(dim);
                indexStatus = "READY";
                pendingRebuild = null;
                rebuildProgress = null;
                cachedIndexExists = true;
                cachedActualDim = dim;
                cachedActualModel = dimensionProvider.getActiveEmbeddingModelKey().orElse(null);
                log.info("Rebuild completed, status=READY");
            } catch (Exception e) {
                indexStatus = "READY";
                lastError = e.getMessage();
                pendingRebuild = null;
                rebuildProgress = null;
                log.error("Rebuild failed: {}", e.getMessage(), e);
            } finally {
                indexOpLock.unlock();
            }
        });
        return true;
    }

    private void doRebuild(int dim) throws Exception {
        String readAlias = kbSegmentConfig.getReadAlias();
        String writeAlias = kbSegmentConfig.getWriteAlias();

        // 1. 解析旧索引物理名(通过 alias)
        var aliasResponse = esClient.indices().getAlias(a -> a.name(readAlias));
        String oldPhysicalIndex = aliasResponse.result().keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No index found for alias: " + readAlias));

        // 2. 统计旧索引文档总数
        long totalDocs = esClient.count(c -> c.index(oldPhysicalIndex)).count();
        log.info("Rebuild: old index [{}] has {} documents to migrate", oldPhysicalIndex, totalDocs);

        // 3. 建新版本索引
        String newPhysicalIndex = newPhysicalIndexName();
        log.info("Rebuild: creating new index [{}] with dim={}", newPhysicalIndex, dim);
        createPhysicalIndex(newPhysicalIndex, dim);

        // 4. 存量数据迁移
        try {
        migrateData(oldPhysicalIndex, newPhysicalIndex, totalDocs);

        // 5. alias 原子切换到新索引
        log.info("Rebuild: switching alias from [{}] to [{}]", oldPhysicalIndex, newPhysicalIndex);
        esClient.indices().updateAliases(u -> u
                .actions(a -> a.remove(r -> r.index(oldPhysicalIndex).alias(readAlias)))
                .actions(a -> a.add(add -> add.index(newPhysicalIndex).alias(readAlias)))
                .actions(a -> a.remove(r -> r.index(oldPhysicalIndex).alias(writeAlias)))
                .actions(a -> a.add(add -> add.index(newPhysicalIndex).alias(writeAlias).isWriteIndex(true)))
        );

        // 6. 删除旧索引
        try {
            esClient.indices().delete(d -> d.index(oldPhysicalIndex));
            log.info("Rebuild: old index [{}] deleted", oldPhysicalIndex);
            } catch (Exception e) {
                log.warn("Rebuild: failed to delete old index [{}]: {}", oldPhysicalIndex, e.getMessage());
            }
            } catch (Exception e) {
                log.error("Rebuild: migration or alias switch failed, cleaning up new index [{}]", newPhysicalIndex, e);
                try {
                    esClient.indices().delete(d -> d.index(newPhysicalIndex));
                    log.info("Rebuild: orphaned new index [{}] deleted", newPhysicalIndex);
                } catch (Exception cleanupEx) {
                    log.warn("Rebuild: failed to delete orphaned new index [{}]: {}", newPhysicalIndex, cleanupEx.getMessage());
                }
                throw e;
            }
    }

    private void migrateData(String oldIndex, String newIndex, long totalDocs) throws Exception {
        long migrated = 0;
        this.rebuildProgress = RebuildProgress.builder()
                .migrated(0).total(totalDocs).phase("MIGRATING").build();

        SearchResponse<SegmentDocument> searchResponse = esClient.search(
                SearchRequest.of(s -> s
                        .index(oldIndex)
                        .size(SCROLL_BATCH_SIZE)
                        .scroll(t -> t.time(SCROLL_KEEP_ALIVE_MINUTES + "m"))),
                SegmentDocument.class);

        String scrollId = searchResponse.scrollId();
        List<Hit<SegmentDocument>> hits = searchResponse.hits().hits();

        while (!hits.isEmpty()) {
            List<SegmentDocument> batch = new ArrayList<>();

            for (Hit<SegmentDocument> hit : hits) {
                SegmentDocument doc = hit.source();
                if (doc == null) continue;

                List<Float> newEmbedding = computeNewEmbedding(doc);
                doc.setEmbedding(newEmbedding);
                batch.add(doc);
            }

            if (!batch.isEmpty()) {
                var bulkBuilder = new BulkRequest.Builder();
                for (SegmentDocument doc : batch) {
                    bulkBuilder.operations(op -> op
                            .index(idx -> idx
                                    .index(newIndex)
                                    .id(doc.getSegmentId())
                                    .document(doc)));
                }
                var bulkResponse = esClient.bulk(bulkBuilder.build());
                for (BulkResponseItem item : bulkResponse.items()) {
                    if (item.error() != null) {
                        log.warn("Rebuild: bulk write error for doc {}: {}",
                                item.id(), item.error().reason());
                    }
                }
            }

            migrated += batch.size();
            this.rebuildProgress = RebuildProgress.builder()
                    .migrated(migrated).total(totalDocs).phase("MIGRATING").build();
            log.info("Rebuild: migrated {}/{} documents", migrated, totalDocs);

            final String currentScrollId = scrollId;
            ScrollResponse<SegmentDocument> scrollResponse = esClient.scroll(
                    ScrollRequest.of(s -> s.scrollId(currentScrollId)
                            .scroll(t -> t.time(SCROLL_KEEP_ALIVE_MINUTES + "m"))),
                    SegmentDocument.class);
            scrollId = scrollResponse.scrollId();
            hits = scrollResponse.hits().hits();
        }

        if (scrollId != null) {
            final String finalScrollId = scrollId;
            esClient.clearScroll(ClearScrollRequest.of(c -> c.scrollId(finalScrollId)));
        }

        this.rebuildProgress = RebuildProgress.builder()
                .migrated(migrated).total(totalDocs).phase("SWITCHING_ALIAS").build();
        log.info("Rebuild: data migration completed, {} documents migrated", migrated);
    }

    private List<Float> computeNewEmbedding(SegmentDocument doc) {
        if ("".equals(doc.getAssetType())) {
            return embeddingPort.embed(doc.getContentText(), "text");
        }
        if (StringUtils.hasText(doc.getThumbnail())) {
            String imageUrl = storagePort.buildAiImageInput(doc.getThumbnail(),
                    SearchObjectStoragePort.AiInputValidity.SHORT);
            return embeddingPort.embed(imageUrl, "image");
        }
        log.warn("Rebuild: doc {} has no contentText or thumbnail, skipping embedding", doc.getSegmentId());
        return List.of();
    }

    // ==================== 1c: status ====================

    @Override
    public SegmentIndexStatusDTO status() {
        if ("READY".equals(indexStatus) && cachedIndexExists != null) {
            return SegmentIndexStatusDTO.builder()
                    .status(indexStatus)
                    .indexExists(cachedIndexExists)
                    .actualDim(cachedActualDim)
                    .actualModel(cachedActualModel)
                    .expectedDim(dimensionProvider.getActiveEmbeddingDimension().orElse(null))
                    .expectedModel(dimensionProvider.getActiveEmbeddingModelKey().orElse(null))
                    .pendingRebuild(pendingRebuild)
                    .rebuildProgress(rebuildProgress)
                    .lastError(lastError)
                    .build();
        }
        String targetName = kbSegmentConfig.getReadTargetName();
        boolean exists;
        Integer actualDim = null;
        String actualModel = null;
        try {
            exists = esClient.indices().exists(e -> e.index(targetName)).value();
            if (exists) {
                Map<String, IndexMappingRecord> mappings = esClient.indices()
                        .getMapping(m -> m.index(targetName)).result();
                for (IndexMappingRecord record : mappings.values()) {
                    if (record.mappings() != null) {
                        var embeddingProp = record.mappings().properties().get("embedding");
                        if (embeddingProp != null && embeddingProp.isDenseVector()) {
                            actualDim = embeddingProp.denseVector().dims();
                        }
                    }
                    break;
                }
            }
            var settingsResp = esClient.indices().getSettings(g -> g.index(targetName));
            IndexState indexState = settingsResp.result().get(targetName);
            if (indexState != null && indexState.settings() != null) {
                IndexSettings indexLevel = indexState.settings().index();
                if (indexLevel != null && indexLevel.otherSettings() != null) {
                    JsonData meta = indexLevel.otherSettings().get("_meta");
                    if (meta != null) {
                        Map<String, String> metaMap = meta.to(Map.class);
                        if (metaMap != null) {
                            actualModel = metaMap.get("embeddingModel");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query index status via alias [{}]: {}", targetName, e.getMessage());
            exists = false;
        }

        Integer expectedDim = dimensionProvider.getActiveEmbeddingDimension().orElse(null);
        String expectedModel = dimensionProvider.getActiveEmbeddingModelKey().orElse(null);

        cachedIndexExists = exists;
        cachedActualDim = actualDim;
        cachedActualModel = actualModel;

        return SegmentIndexStatusDTO.builder()
                .status(indexStatus)
                .indexExists(exists)
                .actualDim(actualDim)
                .actualModel(actualModel)
                .expectedDim(expectedDim)
                .expectedModel(expectedModel)
                .pendingRebuild(pendingRebuild)
                .rebuildProgress(rebuildProgress)
                .lastError(lastError)
                .build();
    }

    // ==================== 1d: retry create ====================

    @Override
    public boolean retryCreate() {
        if (!"NOT_READY".equals(indexStatus)) {
            log.warn("Retry create: status is {}, not NOT_READY", indexStatus);
            return false;
        }
        if (dimensionProvider.getActiveEmbeddingDimension().isEmpty()) {
            log.warn("Retry create: no active embedding dimension configured");
            return false;
        }
        asyncCreate();
        return true;
    }

    // ==================== helpers ====================

    private String loadAndProcessMapping(int dims) throws Exception {
        ClassPathResource resource = new ClassPathResource(MAPPING_PATH);
        String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return json.replace("\"@DIMS@\"", String.valueOf(dims));
    }

    private String newPhysicalIndexName() {
        return kbSegmentConfig.getIndexName() + "_" + System.currentTimeMillis();
    }

    private void createPhysicalIndex(String physicalIndexName, int dim) throws Exception {
        String modelKey = dimensionProvider.getActiveEmbeddingModelKey()
                .orElseThrow(() -> new IllegalStateException("No active embedding model configured"));
        String mappingJson = loadAndProcessMapping(dim);
        InputStream settingsStream = new ClassPathResource(SETTINGS_PATH).getInputStream();
        esClient.indices().create(c -> c
                .index(physicalIndexName)
                .settings(IndexSettings.of(s -> s.withJson(settingsStream)))
                .mappings(TypeMapping.of(m -> m.withJson(new StringReader(mappingJson))))
        );
        log.info("Index [{}] created with dim={}, modelKey={}", physicalIndexName, dim, modelKey);
        writeIndexMeta(physicalIndexName, modelKey);
    }

    private void ensureAliases(String physicalIndexName) {
        bindAliasIfNeeded(kbSegmentConfig.getReadAlias(), physicalIndexName, false);
        bindAliasIfNeeded(kbSegmentConfig.getWriteAlias(), physicalIndexName, true);
    }

    private void writeIndexMeta(String physicalIndexName, String modelKey) {
        try {
            esClient.indices().putSettings(p -> p
                    .index(physicalIndexName)
                    .settings(s -> s.otherSettings(Map.of("_meta",
                            JsonData.of(Map.of("embeddingModel", modelKey))))));
            log.info("Index meta written: index={}, modelKey={}", physicalIndexName, modelKey);
        } catch (Exception e) {
            log.warn("Failed to write index meta [{}]: {}", physicalIndexName, e.getMessage());
        }
    }

    private void bindAliasIfNeeded(String alias, String physicalIndexName, boolean writeAlias) {
        if (alias == null || alias.isBlank()) return;
        try {
            boolean aliasExists = esClient.indices().existsAlias(e -> e.name(alias)).value();
            if (aliasExists) {
                log.info("Alias [{}] already exists, skip binding", alias);
                return;
            }
            esClient.indices().updateAliases(u -> u
                    .actions(a -> a.add(add -> add
                            .index(physicalIndexName)
                            .alias(alias)
                            .isWriteIndex(writeAlias ? Boolean.TRUE : null))));
            log.info("Alias [{}] bound to [{}], writeAlias={}", alias, physicalIndexName, writeAlias);
        } catch (Exception e) {
            log.error("Failed to bind alias [{}] to [{}]: {}", alias, physicalIndexName, e.getMessage());
        }
    }

    @Override
    public String prepareRebuild() {
        SegmentIndexStatusDTO s = status();
        if (!s.isIndexExists() || s.getActualDim() == null || s.getExpectedDim() == null) {
            log.warn("Prepare rebuild: index not ready, indexExists={}, actualDim={}, expectedDim={}",
                    s.isIndexExists(), s.getActualDim(), s.getExpectedDim());
            return null;
        }
        if (s.getActualDim().equals(s.getExpectedDim())) {
            if (Objects.equals(s.getActualModel(), s.getExpectedModel())) {
                log.info("Prepare rebuild: dimensions and model match, no rebuild needed");
                return null;
            }
            log.info("Prepare rebuild: dimensions match but model changed ({} -> {}), triggering rebuild",
                    s.getActualModel(), s.getExpectedModel());
        }
        String reason = buildRebuildReason(s);
        createPendingRebuild(reason, s.getExpectedDim());
        return pendingRebuild.getTaskId();
    }

    private String buildRebuildReason(SegmentIndexStatusDTO s) {
        boolean dimChanged = !Objects.equals(s.getActualDim(), s.getExpectedDim());
        boolean modelChanged = !Objects.equals(s.getActualModel(), s.getExpectedModel());
        if (dimChanged && modelChanged) {
            return "Embedding model and dimension changed: dim " + s.getActualDim() + " -> " + s.getExpectedDim()
                    + ", model " + s.getActualModel() + " -> " + s.getExpectedModel();
        }
        if (dimChanged) {
            return "Embedding dimension changed from " + s.getActualDim() + " to " + s.getExpectedDim();
        }
        return "Embedding model changed from " + s.getActualModel() + " to " + s.getExpectedModel();
    }
}
