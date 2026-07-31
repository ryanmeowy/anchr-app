package com.anchr.core.search.application.impl;

import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.RetrievalHitQueryApi;
import com.anchr.core.search.application.api.RetrievalTopNQueryApi;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalHitQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNResult;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.application.model.SearchRuntimeSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Retrieval query use-case orchestrator over the kb_segment index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalQueryServiceImpl implements RetrievalHitQueryApi, RetrievalTopNQueryApi {

    private final SegmentRepository segmentRepository;
    private final QueryEmbeddingService queryEmbeddingService;
    private final SearchKnowledgeAcl searchKnowledgeAcl;
    private final RuntimeConfigUnit runtimeConfigUnit;
    private final RetrievalRrfFusionPolicy rrfFusionPolicy;
    private final RetrievalRerankPolicy rerankPolicy;
    private final RetrievalResultAssembler resultAssembler;
    private final RetrievalTopNAssembler topNAssembler;

    @Override
    public List<RetrievalHit> query(RetrievalHitQuery query) {
        SearchCriteria criteria = query == null ? null : new SearchCriteria(
                query.query(), query.limit(), query.kbIds(), query.assetIds(),
                List.of(), query.hitTypes(), null, null);
        return searchInternal(criteria, List.of()).items();
    }

    @Override
    public RetrievalTopNResult query(RetrievalTopNQuery query) {
        SearchCriteria criteria = query == null ? null : new SearchCriteria(
                query.query(), query.limit(), query.kbIds(), query.assetIds(), query.assetTypes(),
                query.hitTypes(), query.createdFrom(), query.createdTo());
        SearchResult result = searchInternal(criteria, query == null ? List.of() : query.keywords());
        return new RetrievalTopNResult(
                result.items(),
                topNAssembler.buildWindowFacets(result.items()),
                topNAssembler.buildInsight(
                        result.items(), result.textHits(), result.vectorHits(),
                        result.fusedCount(), result.rerankCount(), result.latencyMs()));
    }

    private SearchResult searchInternal(SearchCriteria query, List<String> keywords) {
        long startMs = System.currentTimeMillis();
        if (query == null || !StringUtils.hasText(query.query())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String rawQuery = query.query().trim();
        SearchRuntimeSettings runtimeConfig = SearchRuntimeSettings.load(runtimeConfigUnit);
        int limit = resolveLimit(query.limit());
        int recallTopK = resolveRecallTopK(limit, runtimeConfig);
        SearchFilter filter = buildFilter(query);
        if (filter.getKbIds().isEmpty()) {
            return emptyResult(startMs);
        }

        List<Float> queryVector = queryEmbeddingService.embedQuery(rawQuery);
        List<String> effectiveKeywords =
                keywords != null && !keywords.isEmpty() ? keywords : List.of();
        List<SegmentHit> textHits =
                segmentRepository.textSearch(rawQuery, effectiveKeywords, recallTopK, filter);
        List<SegmentHit> textVectorHits = segmentRepository.vectorSearch(
                queryVector,
                Math.min(recallTopK, Math.max(1, runtimeConfig.textTopK())),
                runtimeConfig.textSimilarity(),
                routeFilter(filter, false));
        List<SegmentHit> imageVectorHits = segmentRepository.vectorSearch(
                queryVector,
                Math.min(recallTopK, Math.max(1, runtimeConfig.documentImageTopK())),
                runtimeConfig.documentImageSimilarity(),
                routeFilter(filter, true));
        int textHitCount = textHits.size();
        int vectorHitCount = textVectorHits.size() + imageVectorHits.size();
        log.info("kb search recall completed, queryLength={}, kbScope={}, assetScope={}, "
                        + "recallTopK={}, textHits={}, textVectorHits={}, "
                        + "documentImageVectorHits={}, latencyMs={}",
                rawQuery.length(),
                filter.getKbIds().size(),
                filter.getAssetIds() == null ? 0 : filter.getAssetIds().size(),
                recallTopK,
                textHitCount,
                textVectorHits.size(),
                imageVectorHits.size(),
                System.currentTimeMillis() - startMs);

        List<SegmentRerankCandidate> candidates = rrfFusionPolicy.fuse(
                textHits,
                textVectorHits,
                imageVectorHits,
                runtimeConfig.rankConstant()
        );
        int recalledCandidateCount = candidates.size();
        candidates = filterActiveIndexGeneration(candidates);
        int activeGenerationCandidateCount = candidates.size();
        if (recalledCandidateCount != activeGenerationCandidateCount) {
            log.info("kb search generation gate filtered candidates, recalled={}, visible={}, "
                            + "discarded={}",
                    recalledCandidateCount,
                    activeGenerationCandidateCount,
                    recalledCandidateCount - activeGenerationCandidateCount);
        }
        candidates = rrfFusionPolicy.diversify(candidates);
        int fusedCount = candidates.size();

        RetrievalRerankPolicy.Outcome rerankOutcome =
                rerankPolicy.rerank(rawQuery, candidates, limit, runtimeConfig);
        List<SegmentRerankCandidate> rankedCandidates = rerankOutcome.candidates();
        int rerankCount = rankedCandidates.size();
        List<RetrievalHit> segmentResults = rankedCandidates.stream()
                .map(candidate -> resultAssembler.toResult(candidate, rawQuery))
                .filter(Objects::nonNull)
                .toList();
        List<RetrievalHit> allAggregated =
                resultAssembler.aggregateByAsset(segmentResults, limit);
        return new SearchResult(
                allAggregated,
                textHitCount,
                vectorHitCount,
                fusedCount,
                rerankCount,
                System.currentTimeMillis() - startMs
        );
    }

    private SearchResult emptyResult(long startMs) {
        return new SearchResult(
                List.of(), 0, 0, 0, 0,
                System.currentTimeMillis() - startMs);
    }

    private List<SegmentRerankCandidate> filterActiveIndexGeneration(
            List<SegmentRerankCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        for (SegmentRerankCandidate candidate : candidates) {
            Segment segment = candidate == null ? null : candidate.segment();
            if (segment != null && StringUtils.hasText(segment.getAssetId())) {
                assetIds.add(segment.getAssetId().trim());
            }
        }
        if (assetIds.isEmpty()) {
            return List.of();
        }

        Map<String, Long> activeGenerationByAsset =
                searchKnowledgeAcl.findActiveIndexGenerations(assetIds);
        if (activeGenerationByAsset == null || activeGenerationByAsset.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> isActiveGeneration(candidate, activeGenerationByAsset))
                .toList();
    }

    private boolean isActiveGeneration(
            SegmentRerankCandidate candidate,
            Map<String, Long> activeGenerationByAsset
    ) {
        Segment segment = candidate == null ? null : candidate.segment();
        if (segment == null || !StringUtils.hasText(segment.getAssetId())) {
            return false;
        }
        String assetId = segment.getAssetId().trim();
        if (!activeGenerationByAsset.containsKey(assetId)) {
            return false;
        }
        Long activeGeneration = activeGenerationByAsset.get(assetId);
        long expectedGeneration = activeGeneration == null ? 0L : activeGeneration;
        return expectedGeneration == segment.getIndexGeneration();
    }

    private SearchFilter routeFilter(SearchFilter filter, boolean documentImages) {
        List<String> desired = documentImages
                ? List.of(SegmentType.DOCUMENT_IMAGE.name())
                : Arrays.stream(SegmentType.values())
                        .filter(type -> type != SegmentType.DOCUMENT_IMAGE)
                        .map(Enum::name)
                        .toList();
        List<String> requested = filter.getHitTypes();
        List<String> hitTypes = requested == null || requested.isEmpty()
                ? desired
                : desired.stream().filter(requested::contains).toList();
        if (hitTypes.isEmpty()) {
            hitTypes = List.of("__NO_MATCH__");
        }
        return SearchFilter.builder()
                .kbIds(filter.getKbIds())
                .assetIds(filter.getAssetIds())
                .assetTypes(filter.getAssetTypes())
                .hitTypes(hitTypes)
                .createdFrom(filter.getCreatedFrom())
                .createdTo(filter.getCreatedTo())
                .build();
    }

    private int resolveRecallTopK(int limit, SearchRuntimeSettings runtimeConfig) {
        int multiplier = Math.max(1, runtimeConfig.candidateMultiplier());
        int maxCandidates = Math.max(1, runtimeConfig.maxCandidates());
        int recallSize = Math.max(1, limit) * multiplier;
        return Math.min(recallSize, maxCandidates);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return EmbeddingConstant.DEFAULT_TOP_K;
        }
        return Math.min(limit, 200);
    }

    private SearchFilter buildFilter(SearchCriteria query) {
        return SearchFilter.builder()
                .kbIds(searchKnowledgeAcl.resolveVisibleKbIds(query.kbIds()))
                .assetIds(query.assetIds() == null || query.assetIds().isEmpty()
                        ? null : List.copyOf(query.assetIds()))
                .assetTypes(normalizeEnums(query.assetTypes()))
                .hitTypes(normalizeEnums(query.hitTypes()))
                .createdFrom(query.createdFrom())
                .createdTo(query.createdTo())
                .build();
    }

    private List<String> normalizeEnums(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private record SearchResult(
            List<RetrievalHit> items,
            int textHits,
            int vectorHits,
            int fusedCount,
            int rerankCount,
            long latencyMs
    ) {
    }

    private record SearchCriteria(
            String query,
            Integer limit,
            List<String> kbIds,
            List<String> assetIds,
            List<String> assetTypes,
            List<String> hitTypes,
            Long createdFrom,
            Long createdTo
    ) {
    }
}
