package com.anchr.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Elasticsearch repository for unified kb_segment retrieval.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EsSegmentRepository implements SegmentRepository {

    private final ElasticsearchClient esClient;
    private final SegmentIndexConfig kbSegmentConfig;
    private final SegmentIndexManager segmentIndexManager;
    private final SegmentIndexWriteBarrier indexWriteBarrier;
    private void assertIndexReadable() {
        SegmentIndexStatusDTO status = segmentIndexManager.status();
        if (!status.isReadable()) {
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                    "Search index is not readable, current status: " + status.getStatus());
        }
    }

    private void assertIndexWritable() {
        SegmentIndexStatusDTO status = segmentIndexManager.status();
        if (!status.isWritable()) {
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                    "Search index is not writable, current status: " + status.getStatus());
        }
    }

    @Override
    public List<SegmentHit> textSearch(String query, int limit) {
        return textSearch(query, limit, null);
    }

    @Override
    public List<SegmentHit> textSearch(String query, int limit, SearchFilter filter) {
        return textSearch(query, List.of(), limit, filter);
    }

    @Override
    public List<SegmentHit> textSearch(String query, List<String> keywords, int limit, SearchFilter filter) {
        assertIndexReadable();
        if ((!StringUtils.hasText(query) && (keywords == null || keywords.isEmpty())) || limit <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildTextSearchRequest(query != null ? query.trim() : "", keywords, limit, filter);
            SearchResponse<SegmentDocument> response = esClient.search(request, SegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb text search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<SegmentHit> vectorSearch(List<Float> queryVector, int topK) {
        return vectorSearch(queryVector, topK, null);
    }

    @Override
    public List<SegmentHit> vectorSearch(List<Float> queryVector, int topK, SearchFilter filter) {
        return vectorSearch(queryVector, topK, 0.75f, filter);
    }

    @Override
    public List<SegmentHit> vectorSearch(
            List<Float> queryVector, int topK, float similarity, SearchFilter filter) {
        assertIndexReadable();
        if (CollectionUtils.isEmpty(queryVector) || topK <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildVectorSearchRequest(
                    queryVector, topK, similarity, filter);
            SearchResponse<SegmentDocument> response = esClient.search(request, SegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb vector search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public Optional<Segment> findBySegmentId(String segmentId) {
        assertIndexReadable();
        if (!StringUtils.hasText(segmentId)) {
            return Optional.empty();
        }
        try {
            GetResponse<SegmentDocument> response = esClient.get(
                    buildFindBySegmentIdRequest(segmentId),
                    SegmentDocument.class);
            if (response == null || !response.found() || response.source() == null) {
                return Optional.empty();
            }
            SegmentDocument doc = response.source();
            if (!StringUtils.hasText(doc.getSegmentId())) {
                doc.setSegmentId(segmentId.trim());
            }
            return Optional.of(toSegment(doc));
        } catch (Exception e) {
            log.error("kb segment get failed, segmentId={}", segmentId, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    GetRequest buildFindBySegmentIdRequest(String segmentId) {
        return GetRequest.of(g -> g
                .index(kbSegmentConfig.getReadTargetName())
                .id(segmentId.trim())
                .sourceExcludes("embedding"));
    }

    @Override
    public List<Segment> listByAssetId(String kbId,
                                       String assetId,
                                       long activeIndexGeneration,
                                       Integer afterChunkOrder,
                                       String afterSegmentId,
                                       int limit) {
        assertIndexReadable();
        if (!StringUtils.hasText(kbId) || !StringUtils.hasText(assetId) || limit <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildAssetSegmentsRequest(
                    kbId, assetId, activeIndexGeneration,
                    afterChunkOrder, afterSegmentId, limit);
            SearchResponse<SegmentDocument> response =
                    esClient.search(request, SegmentDocument.class);
            if (response == null || response.hits() == null || response.hits().hits() == null) {
                return List.of();
            }
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .map(this::toSegment)
                    .toList();
        } catch (Exception e) {
            log.error("kb asset segment listing failed, kbId={}, assetId={}", kbId, assetId, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    SearchRequest buildAssetSegmentsRequest(
            String kbId,
            String assetId,
            long activeIndexGeneration,
            Integer afterChunkOrder,
            String afterSegmentId,
            int limit
    ) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(kbSegmentConfig.getReadTargetName())
                .size(Math.min(limit, 100))
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t
                                .field("kbId").value(kbId.trim())))
                        .filter(f -> f.term(t -> t
                                .field("assetId").value(assetId.trim())))
                        .filter(indexGenerationFilter(activeIndexGeneration))
                        .mustNot(n -> n.term(t -> t
                                .field("segmentType")
                                .value(SegmentType.IMAGE_VISUAL.name())))))
                .sort(s -> s.field(f -> f
                        .field("chunkOrder")
                        .order(SortOrder.Asc)
                        .missing("_last")))
                .sort(s -> s.field(f -> f
                        .field("segmentId")
                        .order(SortOrder.Asc)))
                .source(src -> src.filter(f -> f.excludes("embedding")));
        if (afterChunkOrder != null && StringUtils.hasText(afterSegmentId)) {
            builder.searchAfter(
                    FieldValue.of(afterChunkOrder.longValue()),
                    FieldValue.of(afterSegmentId.trim()));
        }
        return builder.build();
    }

    @Override
    public void deleteByAssetId(String assetId) {
        if (!StringUtils.hasText(assetId)) {
            return;
        }
        indexWriteBarrier.withWritePermit(() -> doDeleteByAssetId(assetId.trim()));
    }

    @Override
    public void deleteByAssetGeneration(String assetId, long indexGeneration) {
        if (!StringUtils.hasText(assetId) || indexGeneration < 0L) {
            return;
        }
        indexWriteBarrier.withWritePermit(
                () -> doDeleteByAssetGeneration(assetId.trim(), indexGeneration));
    }

    private void doDeleteByAssetId(String assetId) {
        assertIndexWritable();
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(kbSegmentConfig.getWriteTargetName())
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("assetId").value(assetId))));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (response.timedOut()
                    || response.versionConflicts() > 0
                    || (response.failures() != null && !response.failures().isEmpty())) {
                throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "Search index delete did not complete successfully.");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("kb segment delete by asset failed, assetId={}", assetId, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    private void doDeleteByAssetGeneration(String assetId, long indexGeneration) {
        assertIndexWritable();
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(kbSegmentConfig.getWriteTargetName())
                    .refresh(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("assetId").value(assetId)))
                            .filter(indexGenerationFilter(indexGeneration)))));
            assertDeleteCompleted(esClient.deleteByQuery(request));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("kb segment delete by asset generation failed, assetId={}, generation={}",
                    assetId, indexGeneration, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    private Query indexGenerationFilter(long indexGeneration) {
        if (indexGeneration == 0L) {
            return Query.of(q -> q.bool(b -> b
                    .should(s -> s.term(t -> t
                            .field("indexGeneration")
                            .value(0L)))
                    .should(s -> s.bool(missing -> missing
                            .mustNot(n -> n.exists(e -> e.field("indexGeneration")))))
                    .minimumShouldMatch("1")));
        }
        return Query.of(q -> q.term(t -> t
                .field("indexGeneration")
                .value(indexGeneration)));
    }

    private void assertDeleteCompleted(DeleteByQueryResponse response) {
        if (response == null
                || response.timedOut()
                || response.versionConflicts() > 0
                || (response.failures() != null && !response.failures().isEmpty())) {
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                    "Search index delete did not complete successfully.");
        }
    }

    private SearchRequest buildTextSearchRequest1(String query, List<String> keywords, int limit, SearchFilter filter) {
        boolean hasKeywords = keywords != null && keywords.stream().anyMatch(StringUtils::hasText);
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(limit)
                .query(q -> q.bool(b -> {
                    applyFilters(b, filter);
                    if (hasKeywords) {
                        // Original query as low-weight fallback
                        if (StringUtils.hasText(query)) {
                            b.should(sh -> sh.match(m -> m.field("title").query(query).boost(1.0f)));
                            b.should(sh -> sh.match(m -> m.field("contentText").query(query).boost(2.0f)));
                            b.should(sh -> sh.match(m -> m.field("ocrText").query(query).boost(1.5f)));
                        }
                        // Rewritten keywords with higher weight
                        for (String kw : keywords) {
                            if (!StringUtils.hasText(kw)) {
                                continue;
                            }
                            b.should(sh -> sh.match(m -> m.field("title").query(kw).boost(2.5f)));
                            b.should(sh -> sh.match(m -> m.field("contentText").query(kw).boost(4.0f)));
                            b.should(sh -> sh.match(m -> m.field("ocrText").query(kw).boost(3.0f)));
//                            b.should(sh -> sh.match(m -> m.field("tags").query(kw).boost(3.2f)));
                        }
                    } else {
                        // No keywords: original query with full weights
                        b.should(sh -> sh.match(m -> m.field("title").query(query).boost(2.5f)));
                        b.should(sh -> sh.match(m -> m.field("contentText").query(query).boost(4.0f)));
                        b.should(sh -> sh.match(m -> m.field("ocrText").query(query).boost(3.0f)));
//                        b.should(sh -> sh.match(m -> m.field("tags").query(query).boost(3.2f)));
                    }
                    b.minimumShouldMatch("1");
                    return b;
                }))
                .highlight(h -> h
                        .fields("title", f -> f.numberOfFragments(0))
                        .fields("contentText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("ocrText", f -> f.fragmentSize(180).numberOfFragments(1))
//                        .fields("tags", f -> f.numberOfFragments(0))
                )
        );
    }

    /**
     * Builds one term-centric combined-fields query per rewritten keyword.
     * Rewritten keywords are preferred when available; the original query is
     * retained only as the rewrite fallback. Each keyword must match 70% of
     * its analyzed terms, while the outer bool query requires all keywords
     * when there are at most two and 70% when there are more than two.
     */
    SearchRequest buildTextSearchRequest(String query,
                                         List<String> keywords,
                                         int limit,
                                         SearchFilter filter) {
        List<String> effectiveKeywords = keywords == null
                ? List.of()
                : keywords.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
        List<String> textQueries = effectiveKeywords.isEmpty()
                ? List.of(query.trim())
                : effectiveKeywords;

        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(limit)
                .query(q -> q.bool(b -> {
                    applyFilters(b, filter);
                    b.mustNot(n -> n.term(t -> t
                            .field("segmentType")
                            .value(SegmentType.IMAGE_VISUAL.name())));
                    for (String textQuery : textQueries) {
                        b.should(sh -> sh.combinedFields(cf -> cf
                                .query(textQuery)
                                .fields("title^2.5", "contentText^4.0", "ocrText^3.0")
                                .minimumShouldMatch("70%")
                        ));
                    }
                    b.minimumShouldMatch("2<70%");
                    return b;
                }))
                .highlight(h -> h
                        .fields("title", f -> f.numberOfFragments(0))
                        .fields("contentText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("ocrText", f -> f.fragmentSize(180).numberOfFragments(1))
                )
                .source(src -> src.filter(f -> f.excludes("embedding")))
        );
    }

    SearchRequest buildVectorSearchRequest(
            List<Float> queryVector,
            int topK,
            SearchFilter filter
    ) {
        return buildVectorSearchRequest(queryVector, topK, 0.75f, filter);
    }

    SearchRequest buildVectorSearchRequest(
            List<Float> queryVector,
            int topK,
            float similarity,
            SearchFilter filter
    ) {
        int numCandidates = Math.max(EmbeddingConstant.DEFAULT_NUM_CANDIDATES, topK * EmbeddingConstant.NUM_CANDIDATES_FACTOR);
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(topK)
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .knn(k -> {
                    k.field("embedding")
                            .queryVector(queryVector)
                            .k(topK)
                            .numCandidates(numCandidates)
                            .similarity(similarity);
                    applyKnnFilters(k, filter);
                    return k;
                })
        );
    }

    private void applyFilters(BoolQuery.Builder builder,
                              SearchFilter filter) {
        if (filter == null) {
            return;
        }
        if (!CollectionUtils.isEmpty(filter.getKbIds())) {
            builder.filter(f -> f.terms(t -> t.field("kbId").terms(v -> v.value(
                    filter.getKbIds().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetIds())) {
            builder.filter(f -> f.terms(t -> t.field("assetId").terms(v -> v.value(
                    filter.getAssetIds().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetTypes())) {
            builder.filter(f -> f.terms(t -> t.field("assetType").terms(v -> v.value(
                    filter.getAssetTypes().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getHitTypes())) {
            builder.filter(f -> f.terms(t -> t.field("segmentType").terms(v -> v.value(
                    filter.getHitTypes().stream().map(FieldValue::of).toList()))));
        }
        if (filter.getCreatedFrom() != null || filter.getCreatedTo() != null) {
            builder.filter(f -> f.range(r -> r.number(n -> {
                n.field("createdAt");
                if (filter.getCreatedFrom() != null) {
                    n.gte(filter.getCreatedFrom().doubleValue());
                }
                if (filter.getCreatedTo() != null) {
                    n.lte(filter.getCreatedTo().doubleValue());
                }
                return n;
            })));
        }
    }

    private void applyKnnFilters(KnnSearch.Builder builder,
                                 SearchFilter filter) {
        if (filter == null) {
            return;
        }
        if (!CollectionUtils.isEmpty(filter.getKbIds())) {
            builder.filter(f -> f.terms(t -> t.field("kbId").terms(v -> v.value(
                    filter.getKbIds().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetIds())) {
            builder.filter(f -> f.terms(t -> t.field("assetId").terms(v -> v.value(
                    filter.getAssetIds().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetTypes())) {
            builder.filter(f -> f.terms(t -> t.field("assetType").terms(v -> v.value(
                    filter.getAssetTypes().stream().map(FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getHitTypes())) {
            builder.filter(f -> f.terms(t -> t.field("segmentType").terms(v -> v.value(
                    filter.getHitTypes().stream().map(FieldValue::of).toList()))));
        }
        if (filter.getCreatedFrom() != null || filter.getCreatedTo() != null) {
            builder.filter(f -> f.range(r -> r.number(n -> {
                n.field("createdAt");
                if (filter.getCreatedFrom() != null) {
                    n.gte(filter.getCreatedFrom().doubleValue());
                }
                if (filter.getCreatedTo() != null) {
                    n.lte(filter.getCreatedTo().doubleValue());
                }
                return n;
            })));
        }
    }

    private List<SegmentHit> convertHits(SearchResponse<SegmentDocument> response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return List.of();
        }
        return response.hits().hits().stream()
                .map(this::convertSingleHit)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private SegmentHit convertSingleHit(Hit<SegmentDocument> hit) {
        if (hit == null || hit.source() == null || hit.score() == null) {
            return null;
        }
        SegmentDocument doc = hit.source();
        if (!StringUtils.hasText(doc.getSegmentId()) && StringUtils.hasText(hit.id())) {
            doc.setSegmentId(hit.id());
        }
        return SegmentHit.builder()
                .segment(toSegment(doc))
                .rawScore(hit.score())
                .highlights(extractHighlightMap(hit))
                .highlightFields(hit.highlight() == null ? List.of() : List.copyOf(hit.highlight().keySet()))
                .build();
    }

    private List<Segment> convertSegmentHits(SearchResponse<SegmentDocument> response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return List.of();
        }
        return response.hits().hits().stream()
                .map(this::convertSegmentHit)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Segment convertSegmentHit(Hit<SegmentDocument> hit) {
        if (hit == null || hit.source() == null) {
            return null;
        }
        SegmentDocument doc = hit.source();
        if (!StringUtils.hasText(doc.getSegmentId()) && StringUtils.hasText(hit.id())) {
            doc.setSegmentId(hit.id());
        }
        return toSegment(doc);
    }

    private Segment toSegment(SegmentDocument doc) {
        return Segment.builder()
                .segmentId(doc.getSegmentId())
                .kbId(doc.getKbId())
                .assetId(doc.getAssetId())
                .indexGeneration(doc.getIndexGeneration() == null ? 0L : doc.getIndexGeneration())
                .assetType(doc.getAssetType())
                .segmentType(parseSegmentType(doc.getSegmentType()))
                .title(doc.getTitle())
                .contentText(doc.getContentText())
                .ocrText(doc.getOcrText())
                .pageNo(doc.getPageNo())
                .chunkOrder(doc.getChunkOrder())
                .bbox(doc.getBbox())
                .imageWidth(doc.getImageWidth())
                .imageHeight(doc.getImageHeight())
                .embedding(doc.getEmbedding())
                .sourceRef(doc.getSourceRef())
                .thumbnail(doc.getThumbnail())
                .ocrSummary(doc.getOcrSummary())
                .tags(doc.getTags())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private SegmentType parseSegmentType(String segmentType) {
        if (!StringUtils.hasText(segmentType)) {
            return null;
        }
        return SegmentType.valueOf(segmentType.trim().toUpperCase());
    }

    private Map<String, String> extractHighlightMap(Hit<SegmentDocument> hit) {
        Map<String, List<String>> highlightByField = hit.highlight();
        if (highlightByField == null || highlightByField.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : highlightByField.entrySet()) {
            String field = entry.getKey();
            if (!StringUtils.hasText(field) || CollectionUtils.isEmpty(entry.getValue())) {
                continue;
            }
            String snippet = entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            if (StringUtils.hasText(snippet)) {
                normalized.put(field, snippet);
            }
        }
        return normalized;
    }
}
