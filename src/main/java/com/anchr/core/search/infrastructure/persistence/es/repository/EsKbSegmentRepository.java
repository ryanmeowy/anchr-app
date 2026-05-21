package com.anchr.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.common.config.KbSegmentConfig;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.domain.model.KbSearchFilter;
import com.anchr.core.search.domain.model.KbAssetTypeEnum;
import com.anchr.core.search.domain.model.KbSegmentHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.KbSegmentRepository;
import com.anchr.core.search.infrastructure.persistence.es.document.KbSegmentDocument;
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
public class EsKbSegmentRepository implements KbSegmentRepository {

    private final ElasticsearchClient esClient;
    private final KbSegmentConfig kbSegmentConfig;

    @Override
    public List<KbSegmentHit> textSearch(String query, int limit) {
        return textSearch(query, limit, null);
    }

    @Override
    public List<KbSegmentHit> textSearch(String query, int limit, KbSearchFilter filter) {
        if (!StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildTextSearchRequest(query.trim(), limit, filter);
            SearchResponse<KbSegmentDocument> response = esClient.search(request, KbSegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb text search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<KbSegmentHit> vectorSearch(List<Float> queryVector, int topK) {
        return vectorSearch(queryVector, topK, null);
    }

    @Override
    public List<KbSegmentHit> vectorSearch(List<Float> queryVector, int topK, KbSearchFilter filter) {
        if (CollectionUtils.isEmpty(queryVector) || topK <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildVectorSearchRequest(queryVector, topK, filter);
            SearchResponse<KbSegmentDocument> response = esClient.search(request, KbSegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb vector search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public Optional<Segment> findBySegmentId(String segmentId) {
        if (!StringUtils.hasText(segmentId)) {
            return Optional.empty();
        }
        try {
            GetResponse<KbSegmentDocument> response = esClient.get(g -> g
                    .index(kbSegmentConfig.getReadTargetName())
                    .id(segmentId.trim()), KbSegmentDocument.class);
            if (response == null || !response.found() || response.source() == null) {
                return Optional.empty();
            }
            KbSegmentDocument doc = response.source();
            if (!StringUtils.hasText(doc.getSegmentId())) {
                doc.setSegmentId(segmentId.trim());
            }
            return Optional.of(toSegment(doc));
        } catch (Exception e) {
            log.error("kb segment get failed, segmentId={}", segmentId, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<Segment> findNeighborChunks(String assetId, Integer pageNo, Integer chunkOrder, int window) {
        if (!StringUtils.hasText(assetId) || chunkOrder == null || window <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildNeighborChunksRequest(assetId.trim(), pageNo, chunkOrder, window);
            SearchResponse<KbSegmentDocument> response = esClient.search(request, KbSegmentDocument.class);
            return convertSegmentHits(response);
        } catch (Exception e) {
            log.error("kb neighbor chunks search failed, assetId={}, chunkOrder={}", assetId, chunkOrder, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public void deleteByAssetId(String assetId) {
        if (!StringUtils.hasText(assetId)) {
            return;
        }
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(kbSegmentConfig.getWriteTargetName())
                    .query(q -> q.term(t -> t.field("assetId").value(assetId.trim()))));
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            log.error("kb segment delete by asset failed, assetId={}", assetId, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    private SearchRequest buildTextSearchRequest(String query, int limit, KbSearchFilter filter) {
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(limit)
                .query(q -> q.bool(b -> {
                    applyFilters(b, filter);
                    b.should(sh -> sh.match(m -> m.field("title").query(query).boost(2.5f)));
                    b.should(sh -> sh.match(m -> m.field("contentText").query(query).boost(4.0f)));
                    b.should(sh -> sh.match(m -> m.field("ocrText").query(query).boost(3.0f)));
                    b.should(sh -> sh.match(m -> m.field("tags").query(query).boost(3.2f)));
                    b.minimumShouldMatch("1");
                    return b;
                }))
                .highlight(h -> h
                        .fields("title", f -> f.numberOfFragments(0))
                        .fields("contentText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("ocrText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("tags", f -> f.numberOfFragments(0))
                )
        );
    }

    private SearchRequest buildVectorSearchRequest(List<Float> queryVector, int topK, KbSearchFilter filter) {
        int numCandidates = Math.max(EmbeddingConstant.DEFAULT_NUM_CANDIDATES, topK * EmbeddingConstant.NUM_CANDIDATES_FACTOR);
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(topK)
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .knn(k -> {
                    k.field("embedding")
                            .queryVector(queryVector)
                            .k(topK)
                            .numCandidates(numCandidates);
                    applyKnnFilters(k, filter);
                    return k;
                })
        );
    }

    private void applyFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder builder,
                              KbSearchFilter filter) {
        if (filter == null) {
            return;
        }
        if (!CollectionUtils.isEmpty(filter.getKbIds())) {
            builder.filter(f -> f.terms(t -> t.field("kbId").terms(v -> v.value(
                    filter.getKbIds().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetTypes())) {
            builder.filter(f -> f.terms(t -> t.field("assetType").terms(v -> v.value(
                    filter.getAssetTypes().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getHitTypes())) {
            builder.filter(f -> f.terms(t -> t.field("segmentType").terms(v -> v.value(
                    filter.getHitTypes().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
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

    private void applyKnnFilters(co.elastic.clients.elasticsearch._types.KnnSearch.Builder builder,
                                 KbSearchFilter filter) {
        if (filter == null) {
            return;
        }
        if (!CollectionUtils.isEmpty(filter.getKbIds())) {
            builder.filter(f -> f.terms(t -> t.field("kbId").terms(v -> v.value(
                    filter.getKbIds().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getAssetTypes())) {
            builder.filter(f -> f.terms(t -> t.field("assetType").terms(v -> v.value(
                    filter.getAssetTypes().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
        }
        if (!CollectionUtils.isEmpty(filter.getHitTypes())) {
            builder.filter(f -> f.terms(t -> t.field("segmentType").terms(v -> v.value(
                    filter.getHitTypes().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
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

    private SearchRequest buildNeighborChunksRequest(String assetId, Integer pageNo, int chunkOrder, int window) {
        int from = Math.max(0, chunkOrder - window);
        int to = chunkOrder + window;
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(window * 2 + 1)
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .query(q -> q.bool(b -> {
                    b.filter(f -> f.term(t -> t.field("assetId").value(assetId)));
                    b.filter(f -> f.range(r -> r.number(n -> n
                            .field("chunkOrder")
                            .gte((double) from)
                            .lte((double) to))));
                    if (pageNo != null) {
                        b.filter(f -> f.term(t -> t.field("pageNo").value(pageNo)));
                    }
                    return b;
                }))
                .sort(sort -> sort.field(f -> f.field("chunkOrder").order(SortOrder.Asc)))
                .sort(sort -> sort.field(f -> f.field("segmentId").order(SortOrder.Asc)))
        );
    }

    private List<KbSegmentHit> convertHits(SearchResponse<KbSegmentDocument> response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return List.of();
        }
        return response.hits().hits().stream()
                .map(this::convertSingleHit)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private KbSegmentHit convertSingleHit(Hit<KbSegmentDocument> hit) {
        if (hit == null || hit.source() == null || hit.score() == null) {
            return null;
        }
        KbSegmentDocument doc = hit.source();
        if (!StringUtils.hasText(doc.getSegmentId()) && StringUtils.hasText(hit.id())) {
            doc.setSegmentId(hit.id());
        }
        return KbSegmentHit.builder()
                .segment(toSegment(doc))
                .rawScore(hit.score())
                .highlights(extractHighlightMap(hit))
                .highlightFields(hit.highlight() == null ? List.of() : List.copyOf(hit.highlight().keySet()))
                .build();
    }

    private List<Segment> convertSegmentHits(SearchResponse<KbSegmentDocument> response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return List.of();
        }
        return response.hits().hits().stream()
                .map(this::convertSegmentHit)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Segment convertSegmentHit(Hit<KbSegmentDocument> hit) {
        if (hit == null || hit.source() == null) {
            return null;
        }
        KbSegmentDocument doc = hit.source();
        if (!StringUtils.hasText(doc.getSegmentId()) && StringUtils.hasText(hit.id())) {
            doc.setSegmentId(hit.id());
        }
        return toSegment(doc);
    }

    private Segment toSegment(KbSegmentDocument doc) {
        return Segment.builder()
                .segmentId(doc.getSegmentId())
                .kbId(doc.getKbId())
                .assetId(doc.getAssetId())
                .assetType(parseAssetType(doc.getAssetType()))
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

    private KbAssetTypeEnum parseAssetType(String assetType) {
        if (!StringUtils.hasText(assetType)) {
            return null;
        }
        return KbAssetTypeEnum.valueOf(assetType.trim().toUpperCase());
    }

    private SegmentType parseSegmentType(String segmentType) {
        if (!StringUtils.hasText(segmentType)) {
            return null;
        }
        return SegmentType.valueOf(segmentType.trim().toUpperCase());
    }

    private Map<String, String> extractHighlightMap(Hit<KbSegmentDocument> hit) {
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
