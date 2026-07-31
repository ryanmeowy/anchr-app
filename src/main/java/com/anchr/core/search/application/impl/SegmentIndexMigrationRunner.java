package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.ClearScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
final class SegmentIndexMigrationRunner {
    private static final int SCROLL_BATCH_SIZE = 50;
    private static final int SCROLL_KEEP_ALIVE_MINUTES = 5;
    private static final int EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS = 5;
    private static final long EMBEDDING_RATE_LIMIT_BACKOFF_MS = 5_000L;
    private static final long EMBEDDING_CALL_INTERVAL_MS = 500L;

    private final ElasticsearchClient esClient;
    private final SearchObjectStoragePort storagePort;
    private final IdGen idGen;

    MigrationResult migrate(
            String oldIndex,
            String newIndex,
            long totalDocs,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            Consumer<Progress> progressConsumer
    ) throws Exception {
        long processed = 0;
        long projected = 0;
        SegmentRebuildProjectionPlanner projectionPlanner =
                new SegmentRebuildProjectionPlanner(
                        targetProfile.capability(), idGen::nextIdStr);
        progressConsumer.accept(new Progress(0, totalDocs, "MIGRATING"));

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
                progressConsumer.accept(
                        new Progress(processed, totalDocs, "MIGRATING"));
                log.info(
                        "Rebuild: processed {}/{} source documents, projected {} target documents",
                        processed, totalDocs, projected);

                String currentScrollId = scrollId;
                ScrollResponse<SegmentDocument> scrollResponse = esClient.scroll(
                        ScrollRequest.of(s -> s.scrollId(currentScrollId)
                                .scroll(t -> t.time(
                                        SCROLL_KEEP_ALIVE_MINUTES + "m"))),
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

        progressConsumer.accept(
                new Progress(processed, totalDocs, "SWITCHING_ALIAS"));
        log.info("Rebuild: data migration validated, source={}, projected={}, target={}",
                totalDocs, projected, targetDocs);
        return new MigrationResult(totalDocs, processed, targetDocs);
    }

    List<MigrationDocument> prepareMigrationBatch(
            List<Hit<SegmentDocument>> hits,
            EmbeddingProfile targetProfile,
            EmbeddingSession embeddingSession,
            SegmentRebuildProjectionPlanner projectionPlanner
    ) {
        List<MigrationDocument> batch = new ArrayList<>(hits.size());
        for (Hit<SegmentDocument> hit : hits) {
            if (hit == null || hit.source() == null) {
                throw new IllegalStateException(
                        "Rebuild source contains a document without _source");
            }
            SegmentDocument document = hit.source();
            String documentId = StringUtils.hasText(hit.id())
                    ? hit.id()
                    : document.getSegmentId();
            if (!StringUtils.hasText(documentId)) {
                throw new IllegalStateException(
                        "Rebuild source contains a document without an id");
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
                batch.add(new MigrationDocument(target.id(), target.document()));
            }
        }
        return batch;
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

    private List<Float> callEmbeddingWithRetry(
            Supplier<List<Float>> call,
            String documentId,
            String sourceType
    ) {
        RuntimeException lastError = null;
        for (int attempt = 1;
             attempt <= EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS;
             attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (!isRateLimitError(e)
                        || attempt >= EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS) {
                    throw e;
                }
                long waitMs = resolveEmbeddingBackoffMs(attempt);
                log.warn(
                        "Rebuild embedding rate limited, documentId={}, sourceType={}, attempt={}/{}, waitMs={}",
                        documentId,
                        sourceType,
                        attempt,
                        EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS,
                        waitMs);
                sleepUninterruptibly(waitMs);
            }
        }
        throw lastError == null
                ? new IllegalStateException(
                        "Rebuild embedding failed for document " + documentId)
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

    record MigrationDocument(String id, SegmentDocument document) {
    }

    record MigrationResult(
            long sourceCount,
            long processedCount,
            long targetCount
    ) {
    }

    record Progress(long migrated, long total, String phase) {
    }
}
