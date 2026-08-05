package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.model.SearchRebuildRuntimeSettings;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingInput;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
final class SegmentIndexMigrationRunner {

    private final ElasticsearchClient esClient;
    private final SearchObjectStoragePort storagePort;
    private final IdGen idGen;
    private final RuntimeConfigUnit runtimeConfigUnit;

    SearchRebuildRuntimeSettings settings() {
        return SearchRebuildRuntimeSettings.load(runtimeConfigUnit);
    }

    MigrationResult migrate(
            String oldIndex,
            String newIndex,
            long totalDocs,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            Consumer<Progress> progressConsumer
    ) throws Exception {
        SearchRebuildRuntimeSettings settings = settings();
        long processed = 0L;
        long projected = 0L;
        SegmentRebuildProjectionPlanner projectionPlanner =
                new SegmentRebuildProjectionPlanner(
                        targetProfile.capability(), idGen::nextIdStr);
        progressConsumer.accept(new Progress(0, totalDocs, "BACKFILLING"));

        List<FieldValue> searchAfter = null;
        while (true) {
            List<Hit<SegmentDocument>> hits = searchPage(
                    oldIndex, null, searchAfter, settings.sourceBatchSize());
            if (hits.isEmpty()) {
                break;
            }
            List<MigrationDocument> batch = prepareMigrationBatch(
                    hits, targetProfile, embeddingSession, projectionPlanner, settings);
            writeMigrationBatch(newIndex, batch);

            processed += hits.size();
            projected += batch.size();
            progressConsumer.accept(new Progress(processed, totalDocs, "BACKFILLING"));
            log.info(
                    "Rebuild: backfilled {}/{} source documents, projected {} target documents",
                    processed, totalDocs, projected);
            searchAfter = requireSortValues(hits.getLast());
        }
        return new MigrationResult(totalDocs, processed, projected);
    }

    void resyncAsset(
            String oldIndex,
            String newIndex,
            String assetId,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession
    ) throws Exception {
        SearchRebuildRuntimeSettings settings = settings();
        deleteTargetAsset(newIndex, assetId);
        SegmentRebuildProjectionPlanner projectionPlanner =
                new SegmentRebuildProjectionPlanner(
                        targetProfile.capability(), idGen::nextIdStr);
        List<FieldValue> searchAfter = null;
        while (true) {
            List<Hit<SegmentDocument>> hits = searchPage(
                    oldIndex, assetId, searchAfter, settings.sourceBatchSize());
            if (hits.isEmpty()) {
                return;
            }
            writeMigrationBatch(newIndex, prepareMigrationBatch(
                    hits, targetProfile, embeddingSession, projectionPlanner, settings));
            searchAfter = requireSortValues(hits.getLast());
        }
    }

    Validation validateCurrentTarget(
            String oldIndex,
            String newIndex,
            EmbeddingProfile targetProfile
    ) throws Exception {
        SearchRebuildRuntimeSettings settings = settings();
        SegmentRebuildProjectionPlanner planner = new SegmentRebuildProjectionPlanner(
                targetProfile.capability(), idGen::nextIdStr);
        long sourceCount = 0L;
        long projectedCount = 0L;
        List<FieldValue> searchAfter = null;
        while (true) {
            List<Hit<SegmentDocument>> hits = searchPage(
                    oldIndex, null, searchAfter, settings.sourceBatchSize());
            if (hits.isEmpty()) {
                break;
            }
            for (Hit<SegmentDocument> hit : hits) {
                SegmentDocument source = requireSource(hit);
                projectedCount += planner.plan(documentId(hit, source), source).size();
            }
            sourceCount += hits.size();
            searchAfter = requireSortValues(hits.getLast());
        }
        esClient.indices().refresh(r -> r.index(newIndex));
        long targetCount = esClient.count(c -> c.index(newIndex)).count();
        if (projectedCount != targetCount) {
            throw new IllegalStateException(
                    "Rebuild document count mismatch: source=" + sourceCount
                            + ", projected=" + projectedCount
                            + ", target=" + targetCount);
        }
        return new Validation(sourceCount, projectedCount, targetCount);
    }

    List<Segment> reprojectSegments(
            List<Segment> segments,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession
    ) {
        SegmentRebuildProjectionPlanner planner = new SegmentRebuildProjectionPlanner(
                targetProfile.capability(), idGen::nextIdStr);
        List<Hit<SegmentDocument>> hits = segments.stream()
                .map(segment -> Hit.<SegmentDocument>of(hit -> hit
                        .index("profile_reprojection")
                        .id(segment.getSegmentId())
                        .source(toDocument(segment))))
                .toList();
        return prepareMigrationBatch(
                hits, targetProfile, embeddingSession, planner).stream()
                .map(item -> toSegment(item.document()))
                .toList();
    }

    List<MigrationDocument> prepareMigrationBatch(
            List<Hit<SegmentDocument>> hits,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            SegmentRebuildProjectionPlanner projectionPlanner
    ) {
        return prepareMigrationBatch(
                hits, targetProfile, embeddingSession, projectionPlanner, settings());
    }

    private List<MigrationDocument> prepareMigrationBatch(
            List<Hit<SegmentDocument>> hits,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            SegmentRebuildProjectionPlanner projectionPlanner,
            SearchRebuildRuntimeSettings settings
    ) {
        List<MigrationDocument> batch = new ArrayList<>(hits.size());
        List<PendingEmbedding> pending = new ArrayList<>();
        for (Hit<SegmentDocument> hit : hits) {
            SegmentDocument document = requireSource(hit);
            String documentId = documentId(hit, document);
            if (!StringUtils.hasText(document.getSegmentId())) {
                document.setSegmentId(documentId);
            }
            List<SegmentRebuildProjectionPlanner.PlannedDocument> planned =
                    projectionPlanner.plan(documentId, document);
            for (SegmentRebuildProjectionPlanner.PlannedDocument target : planned) {
                EmbeddingProjection projection = target.projection();
                if (projection != null) {
                    String source = projection.inputType()
                            == EmbeddingProjection.InputType.IMAGE
                            ? resolveRebuildImageInput(projection.source())
                            : projection.source();
                    pending.add(new PendingEmbedding(
                            target,
                            new EmbeddingInput(
                                    source, projection.inputType().requestValue())));
                }
                batch.add(new MigrationDocument(target.id(), target.document()));
            }
        }
        applyEmbeddings(pending, targetProfile, embeddingSession, settings);
        return batch;
    }

    private void applyEmbeddings(
            List<PendingEmbedding> pending,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            SearchRebuildRuntimeSettings settings
    ) {
        if (pending.isEmpty()) {
            return;
        }
        Map<String, List<PendingEmbedding>> grouped = new LinkedHashMap<>();
        for (PendingEmbedding item : pending) {
            grouped.computeIfAbsent(
                    item.input().sourceType(), ignored -> new ArrayList<>()).add(item);
        }
        List<EmbeddingBatch> batches = new ArrayList<>();
        for (Map.Entry<String, List<PendingEmbedding>> entry : grouped.entrySet()) {
            List<PendingEmbedding> values = entry.getValue();
            int batchSize = EmbeddingProjection.InputType.TEXT.requestValue()
                    .equals(entry.getKey())
                    ? settings.embeddingBatchSize()
                    : 1;
            for (int offset = 0; offset < values.size(); offset += batchSize) {
                batches.add(new EmbeddingBatch(new ArrayList<>(values.subList(
                        offset,
                        Math.min(values.size(), offset + batchSize)))));
            }
        }

        int concurrency = Math.min(settings.embeddingConcurrency(), batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            List<Future<CompletedEmbeddingBatch>> futures = batches.stream()
                    .map(batch -> executor.submit(() -> embedBatchWithRetry(
                            embeddingSession, batch, settings)))
                    .toList();
            for (Future<CompletedEmbeddingBatch> future : futures) {
                CompletedEmbeddingBatch completed = get(future);
                for (int index = 0; index < completed.batch().items().size(); index++) {
                    PendingEmbedding item = completed.batch().items().get(index);
                    List<Float> vector = completed.vectors().get(index);
                    validateEmbedding(item.target().id(), vector, targetProfile.dimension());
                    item.target().document().setEmbedding(vector);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletedEmbeddingBatch embedBatchWithRetry(
            EmbeddingSession session,
            EmbeddingBatch batch,
            SearchRebuildRuntimeSettings settings
    ) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= settings.rateLimitMaxAttempts(); attempt++) {
            try {
                List<List<Float>> vectors = session.embedBatch(
                        batch.items().stream().map(PendingEmbedding::input).toList());
                if (vectors == null || vectors.size() != batch.items().size()) {
                    int actual = vectors == null ? 0 : vectors.size();
                    throw new IllegalStateException(
                            "Embedding batch response size mismatch: expected "
                                    + batch.items().size() + ", actual " + actual);
                }
                return new CompletedEmbeddingBatch(batch, vectors);
            } catch (RuntimeException error) {
                lastError = error;
                if (!isRateLimitError(error)
                        || attempt >= settings.rateLimitMaxAttempts()) {
                    throw error;
                }
                sleep(resolveEmbeddingBackoffMs(attempt, settings.rateLimitBackoffMs()));
            }
        }
        throw lastError == null
                ? new IllegalStateException("Embedding batch failed")
                : lastError;
    }

    private CompletedEmbeddingBatch get(Future<CompletedEmbeddingBatch> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rebuild embedding interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Rebuild embedding failed", cause);
        }
    }

    private List<Hit<SegmentDocument>> searchPage(
            String index,
            String assetId,
            List<FieldValue> searchAfter,
            int size
    ) throws Exception {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(index)
                .size(size)
                .sort(sort -> sort.field(field -> field
                        .field("assetId").order(SortOrder.Asc)))
                .sort(sort -> sort.field(field -> field
                        .field("indexGeneration").order(SortOrder.Asc)
                        .missing("_first")))
                .sort(sort -> sort.field(field -> field
                        .field("segmentType").order(SortOrder.Desc)
                        .missing("_last")))
                .sort(sort -> sort.field(field -> field
                        .field("segmentId").order(SortOrder.Asc)));
        if (StringUtils.hasText(assetId)) {
            builder.query(query -> query.term(term -> term
                    .field("assetId").value(assetId.trim())));
        }
        if (searchAfter != null && !searchAfter.isEmpty()) {
            builder.searchAfter(searchAfter);
        }
        SearchResponse<SegmentDocument> response = esClient.search(
                builder.build(), SegmentDocument.class);
        return response.hits().hits();
    }

    private List<FieldValue> requireSortValues(Hit<SegmentDocument> hit) {
        if (hit.sort() == null || hit.sort().isEmpty()) {
            throw new IllegalStateException("Rebuild search hit has no sort values");
        }
        return hit.sort();
    }

    private SegmentDocument requireSource(Hit<SegmentDocument> hit) {
        if (hit == null || hit.source() == null) {
            throw new IllegalStateException(
                    "Rebuild source contains a document without _source");
        }
        return hit.source();
    }

    private String documentId(Hit<SegmentDocument> hit, SegmentDocument document) {
        String documentId = StringUtils.hasText(hit.id())
                ? hit.id()
                : document.getSegmentId();
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalStateException(
                    "Rebuild source contains a document without an id");
        }
        return documentId;
    }

    private void deleteTargetAsset(String newIndex, String assetId) throws Exception {
        var response = esClient.deleteByQuery(DeleteByQueryRequest.of(delete -> delete
                .index(newIndex)
                .refresh(true)
                .query(query -> query.term(term -> term
                        .field("assetId").value(assetId.trim())))));
        if (response.timedOut()
                || response.versionConflicts() > 0
                || (response.failures() != null && !response.failures().isEmpty())) {
            throw new IllegalStateException(
                    "Rebuild target asset delete failed: " + assetId);
        }
    }

    private SegmentDocument toDocument(Segment segment) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(segment.getSegmentId());
        document.setKbId(segment.getKbId());
        document.setAssetId(segment.getAssetId());
        document.setIndexGeneration(segment.getIndexGeneration());
        document.setAssetType(segment.getAssetType());
        document.setSegmentType(segment.getSegmentType() == null
                ? null : segment.getSegmentType().name());
        document.setTitle(segment.getTitle());
        document.setContentText(segment.getContentText());
        document.setOcrText(segment.getOcrText());
        document.setPageNo(segment.getPageNo());
        document.setChunkOrder(segment.getChunkOrder());
        document.setBbox(segment.getBbox());
        document.setImageWidth(segment.getImageWidth());
        document.setImageHeight(segment.getImageHeight());
        document.setEmbedding(segment.getEmbedding());
        document.setSourceRef(segment.getSourceRef());
        document.setThumbnail(segment.getThumbnail());
        document.setOcrSummary(segment.getOcrSummary());
        document.setTags(segment.getTags());
        document.setCreatedAt(segment.getCreatedAt());
        return document;
    }

    private Segment toSegment(SegmentDocument document) {
        return Segment.builder()
                .segmentId(document.getSegmentId())
                .kbId(document.getKbId())
                .assetId(document.getAssetId())
                .indexGeneration(document.getIndexGeneration() == null
                        ? 0L : document.getIndexGeneration())
                .assetType(document.getAssetType())
                .segmentType(SegmentType.valueOf(document.getSegmentType()))
                .title(document.getTitle())
                .contentText(document.getContentText())
                .ocrText(document.getOcrText())
                .pageNo(document.getPageNo())
                .chunkOrder(document.getChunkOrder())
                .bbox(document.getBbox())
                .imageWidth(document.getImageWidth())
                .imageHeight(document.getImageHeight())
                .embedding(document.getEmbedding())
                .sourceRef(document.getSourceRef())
                .thumbnail(document.getThumbnail())
                .ocrSummary(document.getOcrSummary())
                .tags(document.getTags())
                .createdAt(document.getCreatedAt())
                .build();
    }

    private void writeMigrationBatch(
            String newIndex,
            List<MigrationDocument> batch
    ) throws Exception {
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
                    "Rebuild bulk write failed for " + failures.size()
                            + " document(s): "
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

    static void validateEmbedding(
            String documentId,
            List<Float> embedding,
            int expectedDim
    ) {
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
                    "Rebuild embedding contains non-finite values for document "
                            + documentId);
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

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("429")
                    || message.contains("Throttling")
                    || message.contains("RateQuota")
                    || message.toLowerCase(Locale.ROOT).contains("rate limit"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long resolveEmbeddingBackoffMs(int attempt, long baseMs) {
        long raw = baseMs * (1L << Math.min(attempt - 1, 4));
        long jitter = Math.max(1L, raw / 5L);
        return raw + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1L);
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(1L, millis));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rebuild embedding retry interrupted", error);
        }
    }

    record MigrationDocument(String id, SegmentDocument document) {
    }

    record MigrationResult(long sourceCount, long processedCount, long targetCount) {
    }

    record Validation(long sourceCount, long projectedCount, long targetCount) {
    }

    record Progress(long migrated, long total, String phase) {
    }

    private record PendingEmbedding(
            SegmentRebuildProjectionPlanner.PlannedDocument target,
            EmbeddingInput input
    ) {
    }

    private record EmbeddingBatch(List<PendingEmbedding> items) {
    }

    private record CompletedEmbeddingBatch(
            EmbeddingBatch batch,
            List<List<Float>> vectors
    ) {
    }
}
