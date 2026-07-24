package com.anchr.core.search.application.impl;

import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.KbScopeResolver;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.RetrievalInsightDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Unified retrieval service over kb_segment index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedSearchServiceImpl implements UnifiedSearchService {

    private static final int MAX_CURSOR_OFFSET = 10_000;

    private final SegmentRepository kbSegmentRepository;
    private final QueryEmbeddingService kbQueryEmbeddingService;
    private final KbScopeResolver kbScopeResolver;
    private final AssetRepository assetRepository;
    private final SearchRerankPort searchRerankPort;
    private final AppSearchProperties appSearchProperties;
    private final MeterRegistry meterRegistry;
    private final ActivityEventService activityEventService;

    @Override
    public List<SearchResultDTO> search(SearchQueryDTO query) {
        return search(query, List.of());
    }

    @Override
    public List<SearchResultDTO> search(SearchQueryDTO query, List<String> keywords) {
        SearchResult result = searchInternal(query, 0, keywords);
        return result.items();
    }

    @Override
    public SearchPageDTO searchPage(SearchQueryDTO query, SearchRewriteResult rewriteResult) {
        List<String> keywords = null == rewriteResult.getKeywords() ? List.of() :rewriteResult.getKeywords();
        String queryStr = rewriteResult.getRewrittenQuery();
        if (StringUtils.hasText(queryStr)) {
            query.setQuery(queryStr);
        }
        SearchResult result = searchInternal(query, decodeCursorOffset(query == null ? null : query.getCursor()), keywords);
        List<SearchResultDTO> pageItems = result.items();
        int offset = result.offset();
        String nextCursor = result.total() > offset + pageItems.size()
                ? encodeCursorOffset(offset + pageItems.size())
                : null;
        RetrievalInsightDTO insight = buildInsight(result);
        SearchPageDTO page = SearchPageDTO.builder()
                .items(pageItems)
                .total(result.total())
                .nextCursor(nextCursor)
                .facets(buildFacets(result.allItems()))
                .insight(insight)
                .build();
        recordSearchEvent(query, result.total());
        return page;
    }

    private SearchResult searchInternal(SearchQueryDTO query, int offset, List<String> keywords) {
        long startMs = System.currentTimeMillis();
        if (query == null || !StringUtils.hasText(query.getQuery())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String rawQuery = query.getQuery().trim();
        int limit = resolveLimit(query.getLimit());
        int pageEnd = Math.max(0, offset) + limit;
        int recallTopK = resolveRecallTopK(pageEnd);
        SearchFilter filter = buildFilter(query);
        if (filter.getKbIds().isEmpty()) {
            return new SearchResult(List.of(), List.of(), 0, Math.max(0, offset), limit,
                    0, 0, 0, 0, System.currentTimeMillis() - startMs);
        }

        List<Float> queryVector = kbQueryEmbeddingService.embedQuery(rawQuery);
        List<String> effectiveKeywords = keywords != null && !keywords.isEmpty() ? keywords : List.of();
        List<SegmentHit> textHits = kbSegmentRepository.textSearch(rawQuery, effectiveKeywords, recallTopK, filter);
        List<SegmentHit> vectorHits = kbSegmentRepository.vectorSearch(queryVector, recallTopK, filter);
        int textHitCount = textHits.size();
        int vectorHitCount = vectorHits.size();
        log.info("kb search recall completed, keyword={}, recallTopK={}, textHits={}, vectorHits={}",
                rawQuery, recallTopK, textHitCount, vectorHitCount);

        List<SegmentRerankCandidate> candidates = fuseCandidates(
                textHits,
                vectorHits,
                appSearchProperties.getRrf().getRankConstant()
        );
        int recalledCandidateCount = candidates.size();
        candidates = filterActiveIndexGeneration(candidates);
        int fusedCount = candidates.size();
        if (recalledCandidateCount != fusedCount) {
            log.info("kb search generation gate filtered candidates, recalled={}, visible={}",
                    recalledCandidateCount, fusedCount);
        }
        RerankOutcome rerankOutcome = applyRerank(rawQuery, candidates, limit);
        List<SegmentRerankCandidate> rankedCandidates = rerankOutcome.candidates();
        int rerankCount = rankedCandidates.size();

        List<SearchResultDTO> segmentResults = rankedCandidates.stream()
                .map(candidate -> toResult(candidate, rawQuery))
                .filter(Objects::nonNull)
                .toList();
        List<SearchResultDTO> allAggregated = aggregateByAsset(segmentResults, pageEnd);
        List<SearchResultDTO> pageItems = page(allAggregated, offset, limit);
        long latencyMs = System.currentTimeMillis() - startMs;
        return new SearchResult(pageItems, allAggregated, allAggregated.size(), Math.max(0, offset), limit,
                textHitCount, vectorHitCount, fusedCount, rerankCount, latencyMs);
    }

    private RetrievalInsightDTO buildInsight(SearchResult result) {
        List<SearchResultDTO> allItems = result.allItems();

        // Pipeline
        RetrievalInsightDTO.PipelineDTO pipeline = RetrievalInsightDTO.PipelineDTO.builder()
                .keywordCandidates(result.textHits())
                .vectorCandidates(result.vectorHits())
                .fusedRetained(result.fusedCount())
                .rerankAdopted(result.rerankCount())
                .build();

        // Relevance distribution
        int high = 0, medium = 0, low = 0;
        for (SearchResultDTO item : allItems) {
            Double score = item.getScore();
            if (score == null) {
                low++;
            } else if (score >= 0.8) {
                high++;
            } else if (score >= 0.5) {
                medium++;
            } else {
                low++;
            }
        }
        RetrievalInsightDTO.RelevanceDistributionDTO relevanceDistribution =
                RetrievalInsightDTO.RelevanceDistributionDTO.builder()
                        .high(high)
                        .medium(medium)
                        .low(low)
                        .build();

        // Risk
        RetrievalInsightDTO.RiskDTO risk = RetrievalInsightDTO.RiskDTO.builder()
                .lowRelevanceCount(low)
                .build();

        // Hit source distribution
        int vectorCount = 0, contentCount = 0, ocrCount = 0, tagCount = 0, titleCount = 0;
        for (SearchResultDTO item : allItems) {
            SearchExplainDTO explain = item.getExplain();
            if (explain == null || explain.getHitSources() == null) {
                continue;
            }
            for (String source : explain.getHitSources()) {
                if (!StringUtils.hasText(source)) {
                    continue;
                }
                switch (source.toUpperCase(Locale.ROOT)) {
                    case "VECTOR" -> vectorCount++;
                    case "CONTENT" -> contentCount++;
                    case "OCR" -> ocrCount++;
                    case "TAG" -> tagCount++;
                    case "TITLE" -> titleCount++;
                    default -> { /* ignore unknown */ }
                }
            }
        }
        RetrievalInsightDTO.HitSourceDistributionDTO hitSourceDistribution =
                RetrievalInsightDTO.HitSourceDistributionDTO.builder()
                        .vectorCount(vectorCount)
                        .contentCount(contentCount)
                        .ocrCount(ocrCount)
                        .tagCount(tagCount)
                        .titleCount(titleCount)
                        .build();

        return RetrievalInsightDTO.builder()
                .pipeline(pipeline)
                .relevanceDistribution(relevanceDistribution)
                .risk(risk)
                .hitSourceDistribution(hitSourceDistribution)
                .latencyMs(result.latencyMs())
                .build();
    }

    private void recordSearchEvent(SearchQueryDTO query, long total) {
        if (query == null) {
            return;
        }
        activityEventService.recordSearchExecuted(query, Math.toIntExact(Math.min(total, Integer.MAX_VALUE)));
    }

    private List<SegmentRerankCandidate> fuseCandidates(List<SegmentHit> textHits,
                                                        List<SegmentHit> vectorHits,
                                                        int rankConstant) {
        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        ingest(textHits, false, Math.max(1, rankConstant), grouped);
        ingest(vectorHits, true, Math.max(1, rankConstant), grouped);

        return grouped.values().stream()
                .sorted(Comparator.comparingDouble(Accumulator::getRrfScore).reversed()
                        .thenComparing(Comparator.comparingInt(Accumulator::getHitCount).reversed())
                        .thenComparing(Comparator.comparingDouble(Accumulator::getBestRawScore).reversed()))
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();
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
                assetRepository.findActiveIndexGenerations(assetIds);
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

    private void ingest(List<SegmentHit> ranking,
                        boolean vectorRoute,
                        int rankConstant,
                        Map<String, Accumulator> grouped) {
        if (ranking == null || ranking.isEmpty()) {
            return;
        }
        for (int i = 0; i < ranking.size(); i++) {
            SegmentHit hit = ranking.get(i);
            Segment segment = hit == null ? null : hit.getSegment();
            String segmentId = segment == null ? null : segment.getSegmentId();
            if (!StringUtils.hasText(segmentId)) {
                continue;
            }
            Accumulator acc = grouped.computeIfAbsent(segmentId, ignored -> new Accumulator());
            acc.rrfScore += reciprocal(rankConstant, i);
            acc.hitCount += 1;
            acc.bestRawScore = Math.max(acc.bestRawScore, hit.getRawScore());
            if (vectorRoute) {
                acc.vectorHit = true;
                if (acc.vectorSource == null) {
                    acc.vectorSource = hit;
                }
                continue;
            }
            if (acc.textSource == null) {
                acc.textSource = hit;
            }
        }
    }

    // RRF(document) = Σ 1 / (k + rank)
    private double reciprocal(int rankConstant, int rankIndex) {
        return 1d / (rankConstant + rankIndex + 1d);
    }

    private SegmentRerankCandidate toCandidate(Accumulator acc) {
        SegmentHit displaySource = acc.textSource != null ? acc.textSource : acc.vectorSource;
        Segment segment = displaySource == null ? null : displaySource.getSegment();
        if (segment == null) {
            return null;
        }
        Map<String, String> highlights = acc.textSource == null || acc.textSource.getHighlights() == null
                ? Map.of()
                : acc.textSource.getHighlights();
        return new SegmentRerankCandidate(
                segment.getSegmentId(),
                segment,
                highlights,
                acc.rrfScore,
                acc.bestRawScore,
                acc.hitCount,
                acc.vectorHit
        );
    }

    private SearchResultDTO toResult(SegmentRerankCandidate candidate, String keyword) {
        Segment segment = candidate.segment();
        Map<String, String> highlights = candidate.highlights();
        boolean titleHit = highlights.containsKey("title") || containsIgnoreCase(segment.getTitle(), keyword);
        boolean contentHit = highlights.containsKey("contentText") || containsIgnoreCase(segment.getContentText(), keyword);
        boolean ocrHit = highlights.containsKey("ocrText") || containsIgnoreCase(segment.getOcrText(), keyword);
        boolean tagHit = hasTagHit(segment, keyword, highlights);

        List<String> hitSources = new ArrayList<>();
        if (candidate.vectorHit()) {
            hitSources.add("VECTOR");
        }
        if (titleHit) {
            hitSources.add("TITLE");
        }
        if (contentHit) {
            hitSources.add("CONTENT");
        }
        if (ocrHit) {
            hitSources.add("OCR");
        }
        if (tagHit) {
            hitSources.add("TAG");
        }

        boolean visualProjection =
                segment.getSegmentType() == SegmentType.IMAGE_VISUAL;
        String content = resolveContent(segment);
        String snippet = visualProjection
                ? "" : pickSnippet(content, highlights);
        SearchResultDTO.Anchor anchor = SearchResultDTO.Anchor.builder()
                .pageNo(segment.getPageNo())
                .chunkOrder(segment.getChunkOrder())
                .bbox(segment.getBbox())
                .imageWidth(segment.getImageWidth())
                .imageHeight(segment.getImageHeight())
                .build();
        return SearchResultDTO.builder()
                .segmentType(toCode(segment.getSegmentType()))
                .title(segment.getTitle())
                .content(content)
                .resultType(toCode(segment.getSegmentType()))
                .assetType(segment.getAssetType())
                .snippet(snippet)
                .pageNo(segment.getPageNo())
                .score(candidate.score())
                .segmentId(segment.getSegmentId())
                .kbId(segment.getKbId())
                .assetId(segment.getAssetId())
                .sourceRef(segment.getSourceRef())
                .anchor(anchor)
                .explain(buildExplain(segment, hitSources, candidate.vectorHit(), titleHit, contentHit, ocrHit, tagHit))
                .build();
    }

    private List<SearchResultDTO> aggregateByAsset(List<SearchResultDTO> rankedSegments, int limit) {
        if (rankedSegments == null || rankedSegments.isEmpty()) {
            return List.of();
        }
        Map<String, SearchResultDTO> aggregatedByAsset = new LinkedHashMap<>();
        for (SearchResultDTO item : rankedSegments) {
            String groupKey = resolveAggregateKey(item);
            SearchResultDTO.TopChunk topChunk = toTopChunk(item);
            SearchResultDTO aggregated = aggregatedByAsset.get(groupKey);
            if (aggregated == null) {
                aggregatedByAsset.put(groupKey, initAggregateResult(item, topChunk));
                continue;
            }
            List<SearchResultDTO.TopChunk> topChunks = aggregated.getTopChunks();
            if (topChunks == null) {
                topChunks = new ArrayList<>();
                aggregated.setTopChunks(topChunks);
            }
            topChunks.add(topChunk);
            int totalHits = aggregated.getTotalHits() == null ? 0 : aggregated.getTotalHits();
            aggregated.setTotalHits(totalHits + 1);
            if (!StringUtils.hasText(aggregated.getThumbnail()) && StringUtils.hasText(item.getThumbnail())) {
                aggregated.setThumbnail(item.getThumbnail());
            }
            if (!StringUtils.hasText(aggregated.getOcrSummary()) && StringUtils.hasText(item.getOcrSummary())) {
                aggregated.setOcrSummary(item.getOcrSummary());
            }
        }
        return aggregatedByAsset.values().stream().limit(limit).toList();
    }

    private SearchResultDTO initAggregateResult(SearchResultDTO primary, SearchResultDTO.TopChunk topChunk) {
        List<SearchResultDTO.TopChunk> topChunks = new ArrayList<>();
        topChunks.add(topChunk);
        return SearchResultDTO.builder()
                .segmentType(primary.getSegmentType())
                .title(primary.getTitle())
                .content(primary.getContent())
                .resultType(primary.getResultType())
                .assetType(primary.getAssetType())
                .snippet(primary.getSnippet())
                .pageNo(primary.getPageNo())
                .score(primary.getScore())
                .explain(primary.getExplain())
                .anchor(primary.getAnchor())
                .thumbnail(primary.getThumbnail())
                .ocrSummary(primary.getOcrSummary())
                .segmentId(primary.getSegmentId())
                .kbId(primary.getKbId())
                .assetId(primary.getAssetId())
                .sourceRef(primary.getSourceRef())
                .totalHits(1)
                .topChunks(topChunks)
                .build();
    }

    private SearchResultDTO.TopChunk toTopChunk(SearchResultDTO segmentItem) {
        return SearchResultDTO.TopChunk.builder()
                .segmentId(segmentItem.getSegmentId())
                .kbId(segmentItem.getKbId())
                .segmentType(segmentItem.getSegmentType())
                .title(segmentItem.getTitle())
                .content(segmentItem.getContent())
                .snippet(segmentItem.getSnippet())
                .explain(segmentItem.getExplain())
                .score(segmentItem.getScore())
                .pageNo(segmentItem.getPageNo())
                .anchor(segmentItem.getAnchor())
                .sourceRef(segmentItem.getSourceRef())
                .thumbnail(segmentItem.getThumbnail())
                .ocrSummary(segmentItem.getOcrSummary())
                .build();
    }

    private String resolveAggregateKey(SearchResultDTO item) {
        if (item == null) {
            return "";
        }
        if (StringUtils.hasText(item.getAssetId())) {
            return item.getAssetId().trim();
        }
        if (StringUtils.hasText(item.getSegmentId())) {
            return "__segment__" + item.getSegmentId().trim();
        }
        if (StringUtils.hasText(item.getSourceRef())) {
            return "__source__" + item.getSourceRef().trim();
        }
        return "__fallback__" + item.hashCode();
    }

    private SearchExplainDTO buildExplain(Segment segment,
                                          List<String> hitSources,
                                            boolean vectorHit,
                                            boolean titleHit,
                                            boolean contentHit,
                                            boolean ocrHit,
                                            boolean tagHit) {
        SearchExplainDTO.MatchedBy matchedBy = SearchExplainDTO.MatchedBy.builder()
                .vector(vectorHit)
                .title(titleHit)
                .content(contentHit)
                .ocr(ocrHit)
                .build();

        SearchExplainDTO.TextSignals textSignals = null;
        SearchExplainDTO.ImageSignals imageSignals = null;

        if (isTextSegment(segment)) {
            textSignals = SearchExplainDTO.TextSignals.builder()
                    .semantic(vectorHit)
                    .keyword(titleHit || contentHit || ocrHit)
                    .pageHit(segment.getPageNo() != null)
                    .chunkHit(segment.getChunkOrder() != null)
                    .build();
        } else if (isImageSegment(segment)) {
            imageSignals = SearchExplainDTO.ImageSignals.builder()
                    .vector(vectorHit)
                    .ocr(ocrHit)
                    .caption(isImageCaptionSegment(segment) && (titleHit || contentHit))
                    .tag(tagHit)
                    .build();
        }

        return SearchExplainDTO.builder()
                .hitSources(hitSources)
                .matchedBy(matchedBy)
                .textSignals(textSignals)
                .imageSignals(imageSignals)
                .build();
    }

    private String pickSnippet(String content, Map<String, String> highlights) {
        if (highlights != null && StringUtils.hasText(highlights.get("contentText"))) {
            return highlights.get("contentText");
        }
        if (highlights != null && StringUtils.hasText(highlights.get("ocrText"))) {
            return highlights.get("ocrText");
        }
        if (highlights != null && StringUtils.hasText(highlights.get("title"))) {
            return highlights.get("title");
        }
        return clip(content, 180);
    }

    private String clip(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }

    private String resolveContent(Segment segment) {
        if (segment == null) {
            return "";
        }
        if (segment.getSegmentType() == SegmentType.IMAGE_VISUAL) {
            return "";
        }
        if (StringUtils.hasText(segment.getContentText())) {
            return segment.getContentText();
        }
        if (StringUtils.hasText(segment.getOcrText())) {
            return segment.getOcrText();
        }
        if (StringUtils.hasText(segment.getTitle())) {
            return segment.getTitle();
        }
        return "";
    }

    private boolean isTextSegment(Segment segment) {
        return segment != null && segment.getSegmentType() == SegmentType.TEXT_CHUNK;
    }

    private boolean isImageSegment(Segment segment) {
        return segment != null
                && segment.getSegmentType() != null
                && segment.getSegmentType().name().startsWith("IMAGE_");
    }

    private boolean isImageCaptionSegment(Segment segment) {
        return segment != null && segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK;
    }

    private boolean hasTagHit(Segment segment, String keyword, Map<String, String> highlights) {
        if (highlights != null && highlights.containsKey("tags")) {
            return true;
        }
        if (segment == null || !StringUtils.hasText(keyword)) {
            return false;
        }
        List<String> tags = segment.getTags();
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        return tags.stream().anyMatch(tag -> containsIgnoreCase(tag, keyword));
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(keyword)
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private RerankOutcome applyRerank(String keyword,
                                      List<SegmentRerankCandidate> candidates,
                                      int limit) {
        if (!StringUtils.hasText(keyword) || candidates.isEmpty()) {
            return new RerankOutcome(candidates, false);
        }
        int windowSize = resolveRerankWindowSize(limit, candidates.size());
        if (windowSize <= 0) {
            return new RerankOutcome(candidates, false);
        }

        List<SegmentRerankCandidate> rerankWindow = new ArrayList<>(candidates.subList(0, windowSize));
        List<SegmentRerankCandidate> untouchedTail = windowSize >= candidates.size()
                ? List.of()
                : candidates.subList(windowSize, candidates.size());
        List<String> docs = rerankWindow.stream().map(this::buildRerankDocument).toList();

        meterRegistry.counter("kb.search.rerank.calls").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<RerankItem> rerankResults;
        try {
            rerankResults = searchRerankPort.rerank(keyword, docs, rerankWindow.size());
        } catch (RuntimeException e) {
            meterRegistry.counter("kb.search.rerank.fallback", "reason", "model_error").increment();
            log.warn("kb search rerank failed, retaining RRF order, candidates={}, windowSize={}, message={}",
                    candidates.size(), windowSize, e.getMessage());
            return new RerankOutcome(candidates, false);
        } finally {
            sample.stop(Timer.builder("kb.search.rerank.latency")
                    .description("KB unified rerank latency")
                    .register(meterRegistry));
        }
        if (rerankResults == null || rerankResults.isEmpty()) {
            meterRegistry.counter("kb.search.rerank.fallback", "reason", "empty_result").increment();
            return new RerankOutcome(candidates, false);
        }

        Map<Integer, Double> rerankScoreByIndex = new HashMap<>();
        for (RerankItem item : rerankResults) {
            if (item == null) {
                continue;
            }
            int index = item.index();
            if (index < 0 || index >= rerankWindow.size()) {
                continue;
            }
            rerankScoreByIndex.put(index, normalizeRerankScore(item.score()));
        }
        if (rerankScoreByIndex.isEmpty()) {
            return new RerankOutcome(candidates, false);
        }

        WeightPair weightPair = resolveFusionWeights();
        List<WindowRankItem> sortedWindow = buildAndSortWindow(
                rerankWindow,
                rerankScoreByIndex,
                weightPair.alpha(),
                weightPair.beta()
        );
        List<SegmentRerankCandidate> merged = new ArrayList<>(candidates.size());
        for (WindowRankItem item : sortedWindow) {
            merged.add(item.candidate());
        }
        merged.addAll(untouchedTail);
        log.info("kb search rerank applied, candidates={}, windowSize={}, scored={}, limit={}, alpha={}, beta={}",
                candidates.size(), windowSize, rerankScoreByIndex.size(), limit, weightPair.alpha(), weightPair.beta());
        return new RerankOutcome(merged, true);
    }

    private List<WindowRankItem> buildAndSortWindow(List<SegmentRerankCandidate> rerankWindow,
                                                    Map<Integer, Double> rerankScoreByIndex,
                                                    double alpha,
                                                    double beta) {
        double maxScore = rerankWindow.stream()
                .mapToDouble(SegmentRerankCandidate::score)
                .max()
                .orElse(0d);
        List<WindowRankItem> items = new ArrayList<>(rerankWindow.size());
        for (int i = 0; i < rerankWindow.size(); i++) {
            SegmentRerankCandidate candidate = rerankWindow.get(i);
            double retrievalScore = maxScore <= 0d ? 0d : candidate.score() / maxScore;
            double rerankScore = rerankScoreByIndex.getOrDefault(i, 0d);
            double fusedScore = alpha * retrievalScore + beta * rerankScore;
            SegmentRerankCandidate updatedCandidate = new SegmentRerankCandidate(
                    candidate.segmentId(),
                    candidate.segment(),
                    candidate.highlights(),
                    fusedScore,
                    candidate.bestRawScore(),
                    candidate.hitCount(),
                    candidate.vectorHit()
            );
            items.add(new WindowRankItem(i, updatedCandidate, retrievalScore, rerankScore, fusedScore));
        }
        items.sort(Comparator
                .comparingDouble(WindowRankItem::fusedScore).reversed()
                .thenComparing(Comparator.comparingDouble(WindowRankItem::retrievalScore).reversed())
                .thenComparing(Comparator.comparingDouble(WindowRankItem::rerankScore).reversed())
                .thenComparingInt(WindowRankItem::index));
        return items;
    }

    private int resolveRecallTopK(int limit) {
        int multiplier = Math.max(1, appSearchProperties.getRrf().getCandidateMultiplier());
        int maxCandidates = Math.max(1, appSearchProperties.getRrf().getMaxCandidates());
        int recallSize = Math.max(1, limit) * multiplier;
        return Math.min(recallSize, maxCandidates);
    }

    /**
     * Reranking every recalled candidate adds model latency and payload cost without improving
     * results that are too deep to reach the requested page. A bounded window concentrates that
     * cost on competitive candidates, keeps enough alternatives to correct imperfect RRF ordering,
     * and prevents very small page sizes or unusually large recalls from making the quality/cost
     * trade-off respectively too narrow or unbounded.
    */
    private int resolveRerankWindowSize(int limit, int candidateSize) {
        if (candidateSize <= 0) {
            return 0;
        }
        AppSearchProperties.Rerank rerank = appSearchProperties.getRerank();
        if (!rerank.isWindowEnabled()) {
            return candidateSize;
        }
        int bounded = getBounded(limit, rerank);
        return Math.min(candidateSize, bounded);
    }

    private static int getBounded(int limit, AppSearchProperties.Rerank rerank) {
        int safeLimit = Math.max(1, limit);
        // A fixed size gives predictable model cost; otherwise scaling with the page preserves enough
        // competition for every result slot instead of using the same window for very different pages.
        int baseSize = rerank.getWindowSize() > 0
                ? rerank.getWindowSize()
                : safeLimit * Math.max(1, rerank.getWindowFactor());
        int minSize = Math.max(1, rerank.getWindowMin());
        int maxSize = Math.max(minSize, rerank.getWindowMax());
        return Math.max(minSize, Math.min(baseSize, maxSize));
    }

    private WeightPair resolveFusionWeights() {
        double alpha = clamp01(appSearchProperties.getRerank().getFusionAlpha());
        double beta = clamp01(appSearchProperties.getRerank().getFusionBeta());
        double sum = alpha + beta;
        if (sum <= 0d) {
            return new WeightPair(1d, 0d);
        }
        return new WeightPair(alpha / sum, beta / sum);
    }

    private double normalizeRerankScore(double score) {
        if (score <= 0d) {
            return 0d;
        }
        return Math.min(score, 1d);
    }

    private double clamp01(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 1d) {
            return 1d;
        }
        return value;
    }

    private String buildRerankDocument(SegmentRerankCandidate candidate) {
        if (candidate == null || candidate.segment() == null) {
            return "";
        }
        Segment segment = candidate.segment();
        StringBuilder sb = new StringBuilder(256);
        appendRerankField(sb, "segmentType", toCode(segment.getSegmentType()));
        appendRerankField(sb, "title", segment.getTitle());
        appendRerankField(sb, "content", segment.getContentText());
        appendRerankField(sb, "ocr", segment.getOcrText());
        if (segment.getTags() != null && !segment.getTags().isEmpty()) {
            appendRerankField(sb, "tags", String.join(", ", segment.getTags()));
        }
        String merged = sb.toString();
        int maxDocChars = Math.max(64, appSearchProperties.getRerank().getMaxDocChars());
        if (merged.length() <= maxDocChars) {
            return merged;
        }
        return merged.substring(0, maxDocChars);
    }

    private void appendRerankField(StringBuilder sb, String field, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(field).append(": ").append(value);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return EmbeddingConstant.DEFAULT_TOP_K;
        }
        return Math.min(limit, 200);
    }

    private SearchFilter buildFilter(SearchQueryDTO query) {
        return SearchFilter.builder()
                .kbIds(kbScopeResolver.resolveVisibleKbIds(query.getKbIds()))
                .assetIds(query.getAssetIdList() == null || query.getAssetIdList().isEmpty() ? null : List.copyOf(query.getAssetIdList()))
                .assetTypes(normalizeEnums(query.getAssetTypes()))
                .hitTypes(normalizeEnums(query.getHitTypes()))
                .createdFrom(query.getDateRange() == null ? null : query.getDateRange().getFrom())
                .createdTo(query.getDateRange() == null ? null : query.getDateRange().getTo())
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

    private List<SearchResultDTO> page(List<SearchResultDTO> items, int offset, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int start = Math.min(Math.max(0, offset), items.size());
        int end = Math.min(items.size(), start + Math.max(1, limit));
        return items.subList(start, end);
    }

    private Map<String, List<SearchPageDTO.FacetItemDTO>> buildFacets(List<SearchResultDTO> items) {
        if (items == null || items.isEmpty()) {
            return Map.of("assetTypes", List.of(), "hitTypes", List.of());
        }
        return Map.of(
                "assetTypes", toFacet(items.stream().map(SearchResultDTO::getAssetType).toList()),
                "hitTypes", toFacet(items.stream().map(SearchResultDTO::getSegmentType).toList())
        );
    }

    private List<SearchPageDTO.FacetItemDTO> toFacet(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.trim();
            counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1L);
        }
        return counts.entrySet().stream()
                .map(entry -> SearchPageDTO.FacetItemDTO.builder()
                        .value(entry.getKey())
                        .count(entry.getValue())
                        .build())
                .toList();
    }

    private int decodeCursorOffset(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            return Math.min(MAX_CURSOR_OFFSET, Math.max(0, Integer.parseInt(decoded)));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "cursor is invalid.");
        }
    }

    private String encodeCursorOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(Math.max(0, offset)).getBytes(StandardCharsets.UTF_8));
    }

    private String toCode(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static final class Accumulator {
        private double rrfScore;
        private int hitCount;
        private double bestRawScore;
        private boolean vectorHit;
        private SegmentHit vectorSource;
        private SegmentHit textSource;

        private Accumulator() {
        }

        private double getRrfScore() {
            return rrfScore;
        }

        private int getHitCount() {
            return hitCount;
        }

        private double getBestRawScore() {
            return bestRawScore;
        }
    }

    private record WeightPair(double alpha, double beta) {
    }

    private record SearchResult(List<SearchResultDTO> items,
                                List<SearchResultDTO> allItems,
                                long total,
                                int offset,
                                int limit,
                                int textHits,
                                int vectorHits,
                                int fusedCount,
                                int rerankCount,
                                long latencyMs) {
    }

    private record WindowRankItem(int index,
                                  SegmentRerankCandidate candidate,
                                  double retrievalScore,
                                  double rerankScore,
                                  double fusedScore) {
    }

    private record RerankOutcome(List<SegmentRerankCandidate> candidates, boolean applied) {
    }
}
