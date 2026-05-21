package com.anchr.core.search.application.impl;

import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.KbQueryEmbeddingService;
import com.anchr.core.search.application.KbScopeResolver;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.KbAssetTypeEnum;
import com.anchr.core.search.domain.model.KbSearchFilter;
import com.anchr.core.search.domain.model.KbSegmentHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.KbSegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.KbSearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchResultDTO;
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

    private static final String STRATEGY_CODE = "KB_RRF";
    private static final String STRATEGY_CODE_RERANK = "KB_RRF_RERANK";

    private final KbSegmentRepository kbSegmentRepository;
    private final KbQueryEmbeddingService kbQueryEmbeddingService;
    private final KbScopeResolver kbScopeResolver;
    private final SearchRerankPort searchRerankPort;
    private final AppSearchProperties appSearchProperties;
    private final MeterRegistry meterRegistry;

    @Override
    public List<KbSearchResultDTO> search(KbSearchQueryDTO query) {
        return searchInternal(query, 0).items();
    }

    @Override
    public KbSearchPageDTO searchPage(KbSearchQueryDTO query) {
        SearchResult result = searchInternal(query, decodeCursorOffset(query == null ? null : query.getCursor()));
        List<KbSearchResultDTO> pageItems = result.items();
        int offset = result.offset();
        int limit = result.limit();
        String nextCursor = result.total() > offset + pageItems.size()
                ? encodeCursorOffset(offset + pageItems.size())
                : null;
        return KbSearchPageDTO.builder()
                .items(pageItems)
                .total(result.total())
                .nextCursor(nextCursor)
                .facets(buildFacets(result.allItems()))
                .build();
    }

    private SearchResult searchInternal(KbSearchQueryDTO query, int offset) {
        if (query == null || !StringUtils.hasText(query.getQuery())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String keyword = query.getQuery().trim();
        int requestTopK = resolveTopK(query.getTopK());
        int limit = resolveLimit(query.getLimit(), requestTopK);
        int pageEnd = Math.max(0, offset) + limit;
        int recallTopK = resolveRecallTopK(requestTopK, pageEnd);
        String requestedStrategyCode = resolveStrategy(query.getStrategy());
        boolean rerankRequested = STRATEGY_CODE_RERANK.equals(requestedStrategyCode);
        KbSearchFilter filter = buildFilter(query);
        if (filter.getKbIds().isEmpty()) {
            return new SearchResult(List.of(), List.of(), 0, Math.max(0, offset), limit);
        }

        List<Float> queryVector = kbQueryEmbeddingService.embedQuery(keyword);
        List<KbSegmentHit> textHits = kbSegmentRepository.textSearch(keyword, recallTopK, filter);
        List<KbSegmentHit> vectorHits = kbSegmentRepository.vectorSearch(queryVector, recallTopK, filter);
        log.info("kb search recall completed, keyword={}, strategy={}, rerankRequested={}, recallTopK={}, textHits={}, vectorHits={}",
                keyword, requestedStrategyCode, rerankRequested, recallTopK, textHits.size(), vectorHits.size());

        List<SegmentRerankCandidate> candidates = fuseCandidates(
                textHits,
                vectorHits,
                appSearchProperties.getRrf().getRankConstant()
        );
        boolean rerankEnabled = rerankRequested && appSearchProperties.getRerank().isEnabled();
        List<SegmentRerankCandidate> rankedCandidates = rerankEnabled
                ? applyRerank(keyword, candidates, limit)
                : candidates;
        String effectiveStrategyCode = rerankEnabled ? STRATEGY_CODE_RERANK : STRATEGY_CODE;

        List<KbSearchResultDTO> segmentResults = rankedCandidates.stream()
                .map(candidate -> toResult(candidate, keyword, effectiveStrategyCode))
                .filter(Objects::nonNull)
                .toList();
        List<KbSearchResultDTO> allAggregated = aggregateByAsset(segmentResults, pageEnd);
        List<KbSearchResultDTO> pageItems = page(allAggregated, offset, limit);
        return new SearchResult(pageItems, allAggregated, allAggregated.size(), Math.max(0, offset), limit);
    }

    private List<SegmentRerankCandidate> fuseCandidates(List<KbSegmentHit> textHits,
                                                        List<KbSegmentHit> vectorHits,
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

    private void ingest(List<KbSegmentHit> ranking,
                        boolean vectorRoute,
                        int rankConstant,
                        Map<String, Accumulator> grouped) {
        if (ranking == null || ranking.isEmpty()) {
            return;
        }
        for (int i = 0; i < ranking.size(); i++) {
            KbSegmentHit hit = ranking.get(i);
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

    private double reciprocal(int rankConstant, int rankIndex) {
        return 1d / (rankConstant + rankIndex + 1d);
    }

    private SegmentRerankCandidate toCandidate(Accumulator acc) {
        KbSegmentHit displaySource = acc.textSource != null ? acc.textSource : acc.vectorSource;
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

    private KbSearchResultDTO toResult(SegmentRerankCandidate candidate, String keyword, String strategyCode) {
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

        String content = resolveContent(segment);
        String snippet = pickSnippet(content, highlights);
        KbSearchResultDTO.Anchor anchor = KbSearchResultDTO.Anchor.builder()
                .pageNo(segment.getPageNo())
                .chunkOrder(segment.getChunkOrder())
                .bbox(segment.getBbox())
                .imageWidth(segment.getImageWidth())
                .imageHeight(segment.getImageHeight())
                .build();
        return KbSearchResultDTO.builder()
                .segmentType(toCode(segment.getSegmentType()))
                .content(content)
                .resultType(toCode(segment.getSegmentType()))
                .assetType(toCode(segment.getAssetType()))
                .snippet(snippet)
                .pageNo(segment.getPageNo())
                .score(candidate.score())
                .segmentId(segment.getSegmentId())
                .kbId(segment.getKbId())
                .assetId(segment.getAssetId())
                .sourceRef(segment.getSourceRef())
                .anchor(anchor)
                .thumbnail(resolveThumbnail(segment))
                .ocrSummary(resolveOcrSummary(segment))
                .explain(buildExplain(segment, strategyCode, hitSources, candidate.vectorHit(), titleHit, contentHit, ocrHit, tagHit))
                .build();
    }

    private List<KbSearchResultDTO> aggregateByAsset(List<KbSearchResultDTO> rankedSegments, int limit) {
        if (rankedSegments == null || rankedSegments.isEmpty()) {
            return List.of();
        }
        Map<String, KbSearchResultDTO> aggregatedByAsset = new LinkedHashMap<>();
        for (KbSearchResultDTO item : rankedSegments) {
            String groupKey = resolveAggregateKey(item);
            KbSearchResultDTO.TopChunk topChunk = toTopChunk(item);
            KbSearchResultDTO aggregated = aggregatedByAsset.get(groupKey);
            if (aggregated == null) {
                aggregatedByAsset.put(groupKey, initAggregateResult(item, topChunk));
                continue;
            }
            List<KbSearchResultDTO.TopChunk> topChunks = aggregated.getTopChunks();
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

    private KbSearchResultDTO initAggregateResult(KbSearchResultDTO primary, KbSearchResultDTO.TopChunk topChunk) {
        List<KbSearchResultDTO.TopChunk> topChunks = new ArrayList<>();
        topChunks.add(topChunk);
        return KbSearchResultDTO.builder()
                .segmentType(primary.getSegmentType())
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

    private KbSearchResultDTO.TopChunk toTopChunk(KbSearchResultDTO segmentItem) {
        return KbSearchResultDTO.TopChunk.builder()
                .segmentId(segmentItem.getSegmentId())
                .kbId(segmentItem.getKbId())
                .segmentType(segmentItem.getSegmentType())
                .snippet(segmentItem.getSnippet())
                .score(segmentItem.getScore())
                .pageNo(segmentItem.getPageNo())
                .anchor(segmentItem.getAnchor())
                .sourceRef(segmentItem.getSourceRef())
                .thumbnail(segmentItem.getThumbnail())
                .ocrSummary(segmentItem.getOcrSummary())
                .build();
    }

    private String resolveAggregateKey(KbSearchResultDTO item) {
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

    private KbSearchExplainDTO buildExplain(Segment segment,
                                            String strategyCode,
                                            List<String> hitSources,
                                            boolean vectorHit,
                                            boolean titleHit,
                                            boolean contentHit,
                                            boolean ocrHit,
                                            boolean tagHit) {
        KbSearchExplainDTO.MatchedBy matchedBy = KbSearchExplainDTO.MatchedBy.builder()
                .vector(vectorHit)
                .title(titleHit)
                .content(contentHit)
                .ocr(ocrHit)
                .build();

        KbSearchExplainDTO.TextSignals textSignals = null;
        KbSearchExplainDTO.ImageSignals imageSignals = null;

        if (isTextSegment(segment)) {
            textSignals = KbSearchExplainDTO.TextSignals.builder()
                    .semantic(vectorHit)
                    .keyword(titleHit || contentHit || ocrHit)
                    .pageHit(segment.getPageNo() != null)
                    .chunkHit(segment.getChunkOrder() != null)
                    .build();
        } else if (isImageSegment(segment)) {
            imageSignals = KbSearchExplainDTO.ImageSignals.builder()
                    .vector(vectorHit)
                    .ocr(ocrHit)
                    .caption(isImageCaptionSegment(segment) && (titleHit || contentHit))
                    .tag(tagHit)
                    .build();
        }

        return KbSearchExplainDTO.builder()
                .strategyEffective(strategyCode)
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

    private String resolveThumbnail(Segment segment) {
        if (segment == null || segment.getAssetType() != KbAssetTypeEnum.IMAGE) {
            return null;
        }
        if (StringUtils.hasText(segment.getThumbnail())) {
            return segment.getThumbnail();
        }
        return segment.getSourceRef();
    }

    private String resolveOcrSummary(Segment segment) {
        if (segment == null || segment.getAssetType() != KbAssetTypeEnum.IMAGE) {
            return null;
        }
        if (StringUtils.hasText(segment.getOcrSummary())) {
            return segment.getOcrSummary();
        }
        return clip(segment.getOcrText(), 180);
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
        return segment != null && segment.getSegmentType() == SegmentType.IMAGE_CAPTION;
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

    private List<SegmentRerankCandidate> applyRerank(String keyword,
                                                     List<SegmentRerankCandidate> candidates,
                                                     int limit) {
        if (!appSearchProperties.getRerank().isEnabled() || !StringUtils.hasText(keyword) || candidates.isEmpty()) {
            return candidates;
        }
        int windowSize = resolveRerankWindowSize(limit, candidates.size());
        if (windowSize <= 0) {
            return candidates;
        }

        List<SegmentRerankCandidate> rerankWindow = new ArrayList<>(candidates.subList(0, windowSize));
        List<SegmentRerankCandidate> untouchedTail = windowSize >= candidates.size()
                ? List.of()
                : candidates.subList(windowSize, candidates.size());
        List<String> docs = rerankWindow.stream().map(this::buildRerankDocument).toList();

        meterRegistry.counter("kb.search.rerank.calls").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<RerankItem> rerankResults = searchRerankPort.rerank(keyword, docs, rerankWindow.size());
        sample.stop(Timer.builder("kb.search.rerank.latency")
                .description("KB unified rerank latency")
                .register(meterRegistry));
        if (rerankResults == null || rerankResults.isEmpty()) {
            meterRegistry.counter("kb.search.rerank.fallback", "reason", "empty_result").increment();
            return candidates;
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
            return candidates;
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
        return merged;
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

    private int resolveRecallTopK(int requestTopK, int limit) {
        int multiplier = Math.max(1, appSearchProperties.getRrf().getCandidateMultiplier());
        int maxCandidates = Math.max(1, appSearchProperties.getRrf().getMaxCandidates());
        int recallByLimit = Math.max(1, limit) * multiplier;
        int recallSize = Math.max(requestTopK, recallByLimit);
        return Math.min(recallSize, maxCandidates);
    }

    private int resolveRerankWindowSize(int limit, int candidateSize) {
        if (candidateSize <= 0) {
            return 0;
        }
        AppSearchProperties.Rerank rerank = appSearchProperties.getRerank();
        if (!rerank.isWindowEnabled()) {
            return candidateSize;
        }
        int safeLimit = Math.max(1, limit);
        int baseSize = rerank.getWindowSize() > 0
                ? rerank.getWindowSize()
                : safeLimit * Math.max(1, rerank.getWindowFactor());
        int minSize = Math.max(1, rerank.getWindowMin());
        int maxSize = Math.max(minSize, rerank.getWindowMax());
        int bounded = Math.max(minSize, Math.min(baseSize, maxSize));
        return Math.min(candidateSize, bounded);
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

    private int resolveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return EmbeddingConstant.DEFAULT_TOP_K;
        }
        return Math.min(topK, 200);
    }

    private int resolveLimit(Integer limit, int topK) {
        if (limit == null || limit <= 0) {
            return topK;
        }
        return Math.min(limit, 200);
    }

    private KbSearchFilter buildFilter(KbSearchQueryDTO query) {
        return KbSearchFilter.builder()
                .kbIds(kbScopeResolver.resolveVisibleKbIds(query.getKbIds()))
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

    private List<KbSearchResultDTO> page(List<KbSearchResultDTO> items, int offset, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int start = Math.min(Math.max(0, offset), items.size());
        int end = Math.min(items.size(), start + Math.max(1, limit));
        return items.subList(start, end);
    }

    private Map<String, List<KbSearchPageDTO.FacetItemDTO>> buildFacets(List<KbSearchResultDTO> items) {
        if (items == null || items.isEmpty()) {
            return Map.of("assetTypes", List.of(), "hitTypes", List.of());
        }
        return Map.of(
                "assetTypes", toFacet(items.stream().map(KbSearchResultDTO::getAssetType).toList()),
                "hitTypes", toFacet(items.stream().map(KbSearchResultDTO::getSegmentType).toList())
        );
    }

    private List<KbSearchPageDTO.FacetItemDTO> toFacet(List<String> values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.trim();
            counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1L);
        }
        return counts.entrySet().stream()
                .map(entry -> KbSearchPageDTO.FacetItemDTO.builder()
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
            return Math.max(0, Integer.parseInt(decoded));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "cursor is invalid.");
        }
    }

    private String encodeCursorOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(Math.max(0, offset)).getBytes(StandardCharsets.UTF_8));
    }

    private String resolveStrategy(String strategy) {
        if (!StringUtils.hasText(strategy)) {
            return STRATEGY_CODE;
        }
        String normalized = strategy.trim().toUpperCase(Locale.ROOT);
        if (STRATEGY_CODE.equals(normalized) || STRATEGY_CODE_RERANK.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(ApiError.INVALID_REQUEST, "unsupported strategy: " + strategy);
    }

    private String toCode(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static final class Accumulator {
        private double rrfScore;
        private int hitCount;
        private double bestRawScore;
        private boolean vectorHit;
        private KbSegmentHit vectorSource;
        private KbSegmentHit textSource;

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

    private record SearchResult(List<KbSearchResultDTO> items,
                                List<KbSearchResultDTO> allItems,
                                long total,
                                int offset,
                                int limit) {
    }

    private record WindowRankItem(int index,
                                  SegmentRerankCandidate candidate,
                                  double retrievalScore,
                                  double rerankScore,
                                  double fusedScore) {
    }
}
