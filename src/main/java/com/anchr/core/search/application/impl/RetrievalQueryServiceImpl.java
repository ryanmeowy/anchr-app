package com.anchr.core.search.application.impl;

import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.RetrievalHitQueryApi;
import com.anchr.core.search.application.api.RetrievalPageQueryApi;
import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalFacet;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalHitQuery;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import com.anchr.core.search.application.api.model.RetrievalPageQuery;
import com.anchr.core.search.application.api.model.RetrievalPageResult;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
public class RetrievalQueryServiceImpl implements RetrievalHitQueryApi, RetrievalPageQueryApi {

    private final SegmentRepository kbSegmentRepository;
    private final QueryEmbeddingService kbQueryEmbeddingService;
    private final SearchKnowledgeAcl searchKnowledgeAcl;
    private final SearchRerankPort searchRerankPort;
    private final AppSearchProperties appSearchProperties;
    private final MeterRegistry meterRegistry;
    private SearchObjectStoragePort objectStoragePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setObjectStoragePort(SearchObjectStoragePort objectStoragePort) {
        this.objectStoragePort = objectStoragePort;
    }

    @Override
    public List<RetrievalHit> query(RetrievalHitQuery query) {
        SearchCriteria criteria = query == null ? null : new SearchCriteria(
                query.query(), query.limit(), query.kbIds(), query.assetIds(),
                List.of(), query.hitTypes(), null, null, null);
        SearchResult result = searchInternal(criteria, List.of());
        return result.items();
    }

    @Override
    public RetrievalPageResult query(RetrievalPageQuery query) {
        SearchCriteria criteria = query == null ? null : new SearchCriteria(
                query.query(), query.limit(), query.kbIds(), query.assetIds(), query.assetTypes(),
                query.hitTypes(), query.createdFrom(), query.createdTo(), query.sort());
        SearchResult result = searchInternal(criteria, query == null ? List.of() : query.keywords());
        return new RetrievalPageResult(
                result.items(), result.total(), buildFacets(result.items()), buildInsight(result));
    }

    private SearchResult searchInternal(SearchCriteria query, List<String> keywords) {
        long startMs = System.currentTimeMillis();
        if (query == null || !StringUtils.hasText(query.query())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String rawQuery = query.query().trim();
        int limit = resolveLimit(query.limit());
        int recallTopK = resolveRecallTopK(limit);
        SearchFilter filter = buildFilter(query);
        if (filter.getKbIds().isEmpty()) {
            return new SearchResult(List.of(), 0,
                    0, 0, 0, 0, System.currentTimeMillis() - startMs);
        }

        List<Float> queryVector = kbQueryEmbeddingService.embedQuery(rawQuery);
        List<String> effectiveKeywords = keywords != null && !keywords.isEmpty() ? keywords : List.of();
        List<SegmentHit> textHits = kbSegmentRepository.textSearch(rawQuery, effectiveKeywords, recallTopK, filter);
        AppSearchProperties.VectorRoutes routes = appSearchProperties.getVectorRoutes();
        List<SegmentHit> textVectorHits = kbSegmentRepository.vectorSearch(
                queryVector,
                Math.min(recallTopK, Math.max(1, routes.getTextTopK())),
                routes.getTextSimilarity(),
                routeFilter(filter, false));
        List<SegmentHit> imageVectorHits = kbSegmentRepository.vectorSearch(
                queryVector,
                Math.min(recallTopK, Math.max(1, routes.getDocumentImageTopK())),
                routes.getDocumentImageSimilarity(),
                routeFilter(filter, true));
        int textHitCount = textHits.size();
        int vectorHitCount = textVectorHits.size() + imageVectorHits.size();
        log.info("kb search recall completed, keyword={}, recallTopK={}, textHits={}, textVectorHits={}, documentImageVectorHits={}",
                rawQuery, recallTopK, textHitCount, textVectorHits.size(), imageVectorHits.size());

        List<SegmentRerankCandidate> candidates = fuseCandidates(
                textHits,
                textVectorHits,
                imageVectorHits,
                appSearchProperties.getRrf().getRankConstant()
        );
        int recalledCandidateCount = candidates.size();
        candidates = filterActiveIndexGeneration(candidates);
        candidates = diversifyByAssetAndSegmentType(candidates);
        int fusedCount = candidates.size();
        if (recalledCandidateCount != fusedCount) {
            log.info("kb search generation gate filtered candidates, recalled={}, visible={}",
                    recalledCandidateCount, fusedCount);
        }
        RerankOutcome rerankOutcome = applyRerank(rawQuery, candidates, limit);
        List<SegmentRerankCandidate> rankedCandidates = rerankOutcome.candidates();
        int rerankCount = rankedCandidates.size();

        List<RetrievalHit> segmentResults = rankedCandidates.stream()
                .map(candidate -> toResult(candidate, rawQuery))
                .filter(Objects::nonNull)
                .toList();
        List<RetrievalHit> allAggregated = aggregateByAsset(segmentResults, limit);
        long latencyMs = System.currentTimeMillis() - startMs;
        return new SearchResult(allAggregated, allAggregated.size(),
                textHitCount, vectorHitCount, fusedCount, rerankCount, latencyMs);
    }

    private RetrievalInsight buildInsight(SearchResult result) {
        List<RetrievalHit> allItems = result.items();

        // Pipeline
        RetrievalInsight.Pipeline pipeline = new RetrievalInsight.Pipeline(
                result.textHits(), result.vectorHits(), result.fusedCount(), result.rerankCount());

        // Relevance distribution
        int high = 0, medium = 0, low = 0;
        for (RetrievalHit item : allItems) {
            Double score = item.score();
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
        RetrievalInsight.RelevanceDistribution relevanceDistribution =
                new RetrievalInsight.RelevanceDistribution(high, medium, low);

        // Risk
        RetrievalInsight.Risk risk = new RetrievalInsight.Risk(low);

        // Hit source distribution
        int vectorCount = 0, contentCount = 0, ocrCount = 0, tagCount = 0, titleCount = 0;
        for (RetrievalHit item : allItems) {
            RetrievalExplain explain = item.explain();
            if (explain == null || explain.hitSources() == null) {
                continue;
            }
            for (String source : explain.hitSources()) {
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
        RetrievalInsight.HitSourceDistribution hitSourceDistribution =
                new RetrievalInsight.HitSourceDistribution(
                        vectorCount, contentCount, ocrCount, tagCount, titleCount);

        return new RetrievalInsight(
                pipeline, relevanceDistribution, risk, hitSourceDistribution, result.latencyMs());
    }

    private List<SegmentRerankCandidate> fuseCandidates(List<SegmentHit> textHits,
                                                        List<SegmentHit> textVectorHits,
                                                        List<SegmentHit> imageVectorHits,
                                                        int rankConstant) {
        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        ingest(textHits, false, Math.max(1, rankConstant), grouped);
        ingest(textVectorHits, true, Math.max(1, rankConstant), grouped);
        ingest(imageVectorHits, true, Math.max(1, rankConstant), grouped);

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

    private RetrievalHit toResult(SegmentRerankCandidate candidate, String keyword) {
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
            hitSources.add(segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE
                    ? "CAPTION" : "CONTENT");
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
        RetrievalAnchor anchor = new RetrievalAnchor(
                segment.getPageNo(), segment.getChunkOrder(), segment.getBbox(),
                segment.getImageWidth(), segment.getImageHeight());
        SearchObjectStoragePort.SignedObjectUrl imagePreview =
                signImagePreview(segment);
        return new RetrievalHit(
                toCode(segment.getSegmentType()), segment.getTitle(), content,
                resultType(segment.getSegmentType()), segment.getAssetType(), snippet,
                segment.getPageNo(), candidate.score(),
                buildExplain(segment, hitSources, candidate.vectorHit(), titleHit, contentHit, ocrHit, tagHit),
                anchor, null, null, null, List.of(), segment.getSegmentId(), segment.getKbId(),
                segment.getAssetId(), segment.getSourceRef(),
                imagePreview == null ? null : imagePreview.url(),
                imagePreview == null ? null : imagePreview.expiresAt());
    }

    private List<RetrievalHit> aggregateByAsset(List<RetrievalHit> rankedSegments, int limit) {
        if (rankedSegments == null || rankedSegments.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalHit> aggregatedByAsset = new LinkedHashMap<>();
        for (RetrievalHit item : rankedSegments) {
            String groupKey = resolveAggregateKey(item);
            RetrievalTopChunk topChunk = toTopChunk(item);
            RetrievalHit aggregated = aggregatedByAsset.get(groupKey);
            if (aggregated == null) {
                aggregatedByAsset.put(groupKey, initAggregateResult(item, topChunk));
                continue;
            }
            List<RetrievalTopChunk> topChunks = new ArrayList<>(aggregated.topChunks());
            topChunks.add(topChunk);
            int totalHits = aggregated.totalHits() == null ? 0 : aggregated.totalHits();
            String thumbnail = StringUtils.hasText(aggregated.thumbnail())
                    ? aggregated.thumbnail() : item.thumbnail();
            String ocrSummary = StringUtils.hasText(aggregated.ocrSummary())
                    ? aggregated.ocrSummary() : item.ocrSummary();
            String aggregateResultType = Objects.equals(aggregated.resultType(), item.resultType())
                    ? aggregated.resultType() : "MIXED";
            aggregatedByAsset.put(groupKey, withAggregation(
                    aggregated, aggregateResultType, thumbnail, ocrSummary,
                    totalHits + 1, topChunks));
        }
        return aggregatedByAsset.values().stream().limit(limit).toList();
    }

    private RetrievalHit initAggregateResult(RetrievalHit primary, RetrievalTopChunk topChunk) {
        return withAggregation(primary, primary.resultType(), primary.thumbnail(), primary.ocrSummary(),
                1, List.of(topChunk));
    }

    private RetrievalHit withAggregation(RetrievalHit source,
                                         String resultType,
                                         String thumbnail,
                                         String ocrSummary,
                                         int totalHits,
                                         List<RetrievalTopChunk> topChunks) {
        return new RetrievalHit(
                source.segmentType(), source.title(), source.content(), resultType, source.assetType(),
                source.snippet(), source.pageNo(), source.score(), source.explain(), source.anchor(),
                thumbnail, ocrSummary, totalHits, topChunks, source.segmentId(), source.kbId(),
                source.assetId(), source.sourceRef(), source.imagePreviewUrl(), source.imagePreviewExpiresAt());
    }

    private RetrievalTopChunk toTopChunk(RetrievalHit segmentItem) {
        return new RetrievalTopChunk(
                segmentItem.segmentId(), segmentItem.kbId(), segmentItem.segmentType(),
                segmentItem.title(), segmentItem.content(), segmentItem.snippet(), segmentItem.explain(),
                segmentItem.score(), segmentItem.pageNo(), segmentItem.anchor(), segmentItem.sourceRef(),
                segmentItem.imagePreviewUrl(), segmentItem.imagePreviewExpiresAt(),
                segmentItem.thumbnail(), segmentItem.ocrSummary());
    }

    private String resolveAggregateKey(RetrievalHit item) {
        if (item == null) {
            return "";
        }
        if (StringUtils.hasText(item.assetId())) {
            return item.assetId().trim();
        }
        if (StringUtils.hasText(item.segmentId())) {
            return "__segment__" + item.segmentId().trim();
        }
        if (StringUtils.hasText(item.sourceRef())) {
            return "__source__" + item.sourceRef().trim();
        }
        return "__fallback__" + item.hashCode();
    }

    private RetrievalExplain buildExplain(Segment segment,
                                          List<String> hitSources,
                                            boolean vectorHit,
                                            boolean titleHit,
                                            boolean contentHit,
                                            boolean ocrHit,
                                            boolean tagHit) {
        RetrievalExplain.MatchedBy matchedBy =
                new RetrievalExplain.MatchedBy(vectorHit, titleHit, contentHit, ocrHit);

        RetrievalExplain.TextSignals textSignals = null;
        RetrievalExplain.ImageSignals imageSignals = null;

        if (isTextSegment(segment)) {
            textSignals = new RetrievalExplain.TextSignals(
                    vectorHit, titleHit || contentHit || ocrHit,
                    segment.getPageNo() != null, segment.getChunkOrder() != null);
        } else if (isImageSegment(segment)) {
            imageSignals = new RetrievalExplain.ImageSignals(
                    vectorHit, ocrHit,
                    isImageCaptionSegment(segment) && (titleHit || contentHit), tagHit);
        }

        return new RetrievalExplain(hitSources, matchedBy, textSignals, imageSignals);
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
                && (segment.getSegmentType().name().startsWith("IMAGE_")
                        || segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE);
    }

    private boolean isImageCaptionSegment(Segment segment) {
        return segment != null && (segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK
                || segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE);
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

    private String resultType(SegmentType segmentType) {
        if (segmentType == null) return null;
        return segmentType == SegmentType.IMAGE_VISUAL
                || segmentType == SegmentType.IMAGE_OCR_BLOCK
                || segmentType == SegmentType.DOCUMENT_IMAGE
                ? "IMAGE" : "TEXT";
    }

    private SearchObjectStoragePort.SignedObjectUrl signImagePreview(Segment segment) {
        if (objectStoragePort == null || segment == null
                || segment.getSegmentType() != SegmentType.DOCUMENT_IMAGE
                || !StringUtils.hasText(segment.getSourceRef())) {
            return null;
        }
        try {
            return objectStoragePort.buildPreviewUrl(
                    segment.getSourceRef().trim());
        } catch (RuntimeException exception) {
            log.warn("embedded image preview signing failed, segmentId={}: {}",
                    segment.getSegmentId(), exception.getMessage());
            return null;
        }
    }

    private SearchFilter routeFilter(SearchFilter filter, boolean documentImages) {
        List<String> desired = documentImages
                ? List.of(SegmentType.DOCUMENT_IMAGE.name())
                : java.util.Arrays.stream(SegmentType.values())
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

    private List<SegmentRerankCandidate> diversifyByAssetAndSegmentType(
            List<SegmentRerankCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        List<SegmentRerankCandidate> diversified = new ArrayList<>();
        for (SegmentRerankCandidate candidate : candidates) {
            Segment segment = candidate == null ? null : candidate.segment();
            if (segment == null) continue;
            String key = Objects.toString(segment.getAssetId(), "") + "\n"
                    + Objects.toString(segment.getSegmentType(), "");
            int count = counts.getOrDefault(key, 0);
            if (count >= 3) continue;
            counts.put(key, count + 1);
            diversified.add(candidate);
        }
        return List.copyOf(diversified);
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

    private Map<String, List<RetrievalFacet>> buildFacets(List<RetrievalHit> items) {
        if (items == null || items.isEmpty()) {
            return Map.of("assetTypes", List.of(), "hitTypes", List.of());
        }
        return Map.of(
                "assetTypes", toFacet(items.stream().map(RetrievalHit::assetType).toList()),
                "hitTypes", toFacet(items.stream().map(RetrievalHit::segmentType).toList())
        );
    }

    private List<RetrievalFacet> toFacet(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.trim();
            counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1L);
        }
        return counts.entrySet().stream()
                .map(entry -> new RetrievalFacet(entry.getKey(), entry.getValue()))
                .toList();
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

    private record SearchResult(List<RetrievalHit> items,
                                long total,
                                int textHits,
                                int vectorHits,
                                int fusedCount,
                                int rerankCount,
                                long latencyMs) {
    }

    private record SearchCriteria(
            String query,
            Integer limit,
            List<String> kbIds,
            List<String> assetIds,
            List<String> assetTypes,
            List<String> hitTypes,
            Long createdFrom,
            Long createdTo,
            String sort
    ) {
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
