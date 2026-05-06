package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.constant.EmbeddingConstant;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.KbQueryEmbeddingService;
import com.smart.vision.core.search.application.UnifiedSearchService;
import com.smart.vision.core.search.config.AppSearchProperties;
import com.smart.vision.core.search.domain.model.KbSegmentHit;
import com.smart.vision.core.search.domain.model.SegmentRerankCandidate;
import com.smart.vision.core.search.domain.port.KbSegmentSearchPort;
import com.smart.vision.core.search.domain.port.SearchRerankPort;
import com.smart.vision.core.search.domain.port.SearchRerankPort.RerankItem;
import com.smart.vision.core.search.infrastructure.persistence.es.document.KbSegmentDocument;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchExplainDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchResultDTO;
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

    private final KbSegmentSearchPort kbSegmentSearchPort;
    private final KbQueryEmbeddingService kbQueryEmbeddingService;
    private final SearchRerankPort searchRerankPort;
    private final AppSearchProperties appSearchProperties;
    private final MeterRegistry meterRegistry;

    @Override
    public List<KbSearchResultDTO> search(KbSearchQueryDTO query) {
        if (query == null || !StringUtils.hasText(query.getQuery())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String keyword = query.getQuery().trim();
        int requestTopK = resolveTopK(query.getTopK());
        int limit = resolveLimit(query.getLimit(), requestTopK);
        int recallTopK = resolveRecallTopK(requestTopK, limit);
        String requestedStrategyCode = resolveStrategy(query.getStrategy());
        boolean rerankRequested = STRATEGY_CODE_RERANK.equals(requestedStrategyCode);

        List<Float> queryVector = kbQueryEmbeddingService.embedQuery(keyword);
        List<KbSegmentHit> textHits = kbSegmentSearchPort.textSearch(keyword, recallTopK);
        List<KbSegmentHit> vectorHits = kbSegmentSearchPort.vectorSearch(queryVector, recallTopK);
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
        return aggregateByAsset(segmentResults, limit);
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
            KbSegmentDocument doc = hit == null ? null : hit.getDocument();
            String segmentId = doc == null ? null : doc.getSegmentId();
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
        KbSegmentDocument doc = displaySource == null ? null : displaySource.getDocument();
        if (doc == null) {
            return null;
        }
        Map<String, String> highlights = acc.textSource == null || acc.textSource.getHighlights() == null
                ? Map.of()
                : acc.textSource.getHighlights();
        return new SegmentRerankCandidate(
                doc.getSegmentId(),
                doc,
                highlights,
                acc.rrfScore,
                acc.bestRawScore,
                acc.hitCount,
                acc.vectorHit
        );
    }

    private KbSearchResultDTO toResult(SegmentRerankCandidate candidate, String keyword, String strategyCode) {
        KbSegmentDocument doc = candidate.document();
        Map<String, String> highlights = candidate.highlights();
        boolean titleHit = highlights.containsKey("title") || containsIgnoreCase(doc.getTitle(), keyword);
        boolean contentHit = highlights.containsKey("contentText") || containsIgnoreCase(doc.getContentText(), keyword);
        boolean ocrHit = highlights.containsKey("ocrText") || containsIgnoreCase(doc.getOcrText(), keyword);
        boolean tagHit = hasTagHit(doc, keyword, highlights);

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

        String content = resolveContent(doc);
        String snippet = pickSnippet(content, highlights);
        KbSearchResultDTO.Anchor anchor = KbSearchResultDTO.Anchor.builder()
                .pageNo(doc.getPageNo())
                .chunkOrder(doc.getChunkOrder())
                .bbox(doc.getBbox())
                .imageWidth(doc.getImageWidth())
                .imageHeight(doc.getImageHeight())
                .build();
        return KbSearchResultDTO.builder()
                .segmentType(doc.getSegmentType())
                .content(content)
                .resultType(doc.getSegmentType())
                .assetType(doc.getAssetType())
                .snippet(snippet)
                .pageNo(doc.getPageNo())
                .score(candidate.score())
                .segmentId(doc.getSegmentId())
                .assetId(doc.getAssetId())
                .sourceRef(doc.getSourceRef())
                .anchor(anchor)
                .thumbnail(resolveThumbnail(doc))
                .ocrSummary(resolveOcrSummary(doc))
                .explain(buildExplain(doc, strategyCode, hitSources, candidate.vectorHit(), titleHit, contentHit, ocrHit, tagHit))
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
                .assetId(primary.getAssetId())
                .sourceRef(primary.getSourceRef())
                .totalHits(1)
                .topChunks(topChunks)
                .build();
    }

    private KbSearchResultDTO.TopChunk toTopChunk(KbSearchResultDTO segmentItem) {
        return KbSearchResultDTO.TopChunk.builder()
                .segmentId(segmentItem.getSegmentId())
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

    private KbSearchExplainDTO buildExplain(KbSegmentDocument doc,
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

        if (isTextSegment(doc)) {
            textSignals = KbSearchExplainDTO.TextSignals.builder()
                    .semantic(vectorHit)
                    .keyword(titleHit || contentHit || ocrHit)
                    .pageHit(doc.getPageNo() != null)
                    .chunkHit(doc.getChunkOrder() != null)
                    .build();
        } else if (isImageSegment(doc)) {
            imageSignals = KbSearchExplainDTO.ImageSignals.builder()
                    .vector(vectorHit)
                    .ocr(ocrHit)
                    .caption(isImageCaptionSegment(doc) && (titleHit || contentHit))
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

    private String resolveContent(KbSegmentDocument doc) {
        if (doc == null) {
            return "";
        }
        if (StringUtils.hasText(doc.getContentText())) {
            return doc.getContentText();
        }
        if (StringUtils.hasText(doc.getOcrText())) {
            return doc.getOcrText();
        }
        if (StringUtils.hasText(doc.getTitle())) {
            return doc.getTitle();
        }
        return "";
    }

    private String resolveThumbnail(KbSegmentDocument doc) {
        if (doc == null || !"IMAGE".equalsIgnoreCase(doc.getAssetType())) {
            return null;
        }
        if (StringUtils.hasText(doc.getThumbnail())) {
            return doc.getThumbnail();
        }
        return doc.getSourceRef();
    }

    private String resolveOcrSummary(KbSegmentDocument doc) {
        if (doc == null || !"IMAGE".equalsIgnoreCase(doc.getAssetType())) {
            return null;
        }
        if (StringUtils.hasText(doc.getOcrSummary())) {
            return doc.getOcrSummary();
        }
        return clip(doc.getOcrText(), 180);
    }

    private boolean isTextSegment(KbSegmentDocument doc) {
        return doc != null && "TEXT_CHUNK".equalsIgnoreCase(doc.getSegmentType());
    }

    private boolean isImageSegment(KbSegmentDocument doc) {
        return doc != null
                && StringUtils.hasText(doc.getSegmentType())
                && doc.getSegmentType().toUpperCase(Locale.ROOT).startsWith("IMAGE_");
    }

    private boolean isImageCaptionSegment(KbSegmentDocument doc) {
        return doc != null && "IMAGE_CAPTION".equalsIgnoreCase(doc.getSegmentType());
    }

    private boolean hasTagHit(KbSegmentDocument doc, String keyword, Map<String, String> highlights) {
        if (highlights != null && highlights.containsKey("tags")) {
            return true;
        }
        if (doc == null || !StringUtils.hasText(keyword)) {
            return false;
        }
        List<String> tags = doc.getTags();
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

        meterRegistry.counter("smartvision.kb.search.rerank.calls").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<RerankItem> rerankResults = searchRerankPort.rerank(keyword, docs, rerankWindow.size());
        sample.stop(Timer.builder("smartvision.kb.search.rerank.latency")
                .description("KB unified rerank latency")
                .register(meterRegistry));
        if (rerankResults == null || rerankResults.isEmpty()) {
            meterRegistry.counter("smartvision.kb.search.rerank.fallback", "reason", "empty_result").increment();
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
                    candidate.document(),
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
        if (candidate == null || candidate.document() == null) {
            return "";
        }
        KbSegmentDocument doc = candidate.document();
        StringBuilder sb = new StringBuilder(256);
        appendRerankField(sb, "segmentType", doc.getSegmentType());
        appendRerankField(sb, "title", doc.getTitle());
        appendRerankField(sb, "content", doc.getContentText());
        appendRerankField(sb, "ocr", doc.getOcrText());
        if (doc.getTags() != null && !doc.getTags().isEmpty()) {
            appendRerankField(sb, "tags", String.join(", ", doc.getTags()));
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

    private record WindowRankItem(int index,
                                  SegmentRerankCandidate candidate,
                                  double retrievalScore,
                                  double rerankScore,
                                  double fusedScore) {
    }
}
