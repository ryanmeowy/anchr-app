package com.smart.vision.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.smart.vision.core.common.config.KbSegmentConfig;
import com.smart.vision.core.common.constant.EmbeddingConstant;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.InfraException;
import com.smart.vision.core.search.domain.model.KbSegmentHit;
import com.smart.vision.core.search.domain.model.KbAssetTypeEnum;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.model.SegmentType;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.infrastructure.persistence.es.document.KbSegmentDocument;
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
        if (!StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildTextSearchRequest(query.trim(), limit);
            SearchResponse<KbSegmentDocument> response = esClient.search(request, KbSegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb text search failed", e);
            throw new InfraException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<KbSegmentHit> vectorSearch(List<Float> queryVector, int topK) {
        if (CollectionUtils.isEmpty(queryVector) || topK <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = buildVectorSearchRequest(queryVector, topK);
            SearchResponse<KbSegmentDocument> response = esClient.search(request, KbSegmentDocument.class);
            return convertHits(response);
        } catch (Exception e) {
            log.error("kb vector search failed", e);
            throw new InfraException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
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
            throw new InfraException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    private SearchRequest buildTextSearchRequest(String query, int limit) {
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(limit)
                .query(q -> q.bool(b -> b
                        .should(sh -> sh.match(m -> m.field("title").query(query).boost(2.5f)))
                        .should(sh -> sh.match(m -> m.field("contentText").query(query).boost(4.0f)))
                        .should(sh -> sh.match(m -> m.field("ocrText").query(query).boost(3.0f)))
                        .should(sh -> sh.match(m -> m.field("tags").query(query).boost(3.2f)))
                        .minimumShouldMatch("1")
                ))
                .highlight(h -> h
                        .fields("title", f -> f.numberOfFragments(0))
                        .fields("contentText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("ocrText", f -> f.fragmentSize(180).numberOfFragments(1))
                        .fields("tags", f -> f.numberOfFragments(0))
                )
        );
    }

    private SearchRequest buildVectorSearchRequest(List<Float> queryVector, int topK) {
        int numCandidates = Math.max(EmbeddingConstant.DEFAULT_NUM_CANDIDATES, topK * EmbeddingConstant.NUM_CANDIDATES_FACTOR);
        return SearchRequest.of(s -> s
                .index(kbSegmentConfig.getReadTargetName())
                .size(topK)
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .knn(k -> k
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(topK)
                        .numCandidates(numCandidates)
                )
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

    private Segment toSegment(KbSegmentDocument doc) {
        return Segment.builder()
                .segmentId(doc.getSegmentId())
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
