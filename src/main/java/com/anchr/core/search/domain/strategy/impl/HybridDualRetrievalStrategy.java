package com.anchr.core.search.domain.strategy.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import com.anchr.core.search.domain.ranking.DualRouteRrfFusionService;
import com.anchr.core.search.domain.repository.ImageSearchRepository;
import com.anchr.core.search.domain.strategy.RetrievalStrategy;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_TOP_K;
import static com.anchr.core.common.constant.SearchConstant.DEFAULT_RESULT_LIMIT;

/**
 * New hybrid retrieval strategy: one vector query + one text query, then app-layer RRF fusion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridDualRetrievalStrategy implements RetrievalStrategy {

    private final ImageSearchRepository imageSearchRepository;
    private final DualRouteRrfFusionService dualRouteRrfFusionService;
    private final SearchRerankPort searchRerankPort;
    private final MeterRegistry meterRegistry;
    private final AppSearchProperties appSearchProperties;

    private int rrfRankConstant;
    private int rrfCandidateMultiplier;
    private int rrfMaxCandidates;
    private boolean rerankEnabled;
    private int rerankMaxDocChars;
    private boolean rerankWindowEnabled;
    private int rerankWindowSize;
    private int rerankWindowFactor;
    private int rerankWindowMin;
    private int rerankWindowMax;
    private double rerankFusionAlpha;
    private double rerankFusionBeta;

    @PostConstruct
    void initSearchConfig() {
        rrfRankConstant = appSearchProperties.getRrf().getRankConstant();
        rrfCandidateMultiplier = appSearchProperties.getRrf().getCandidateMultiplier();
        rrfMaxCandidates = appSearchProperties.getRrf().getMaxCandidates();
        rerankEnabled = appSearchProperties.getRerank().isEnabled();
        rerankMaxDocChars = appSearchProperties.getRerank().getMaxDocChars();
        rerankWindowEnabled = appSearchProperties.getRerank().isWindowEnabled();
        rerankWindowSize = appSearchProperties.getRerank().getWindowSize();
        rerankWindowFactor = appSearchProperties.getRerank().getWindowFactor();
        rerankWindowMin = appSearchProperties.getRerank().getWindowMin();
        rerankWindowMax = appSearchProperties.getRerank().getWindowMax();
        rerankFusionAlpha = appSearchProperties.getRerank().getFusionAlpha();
        rerankFusionBeta = appSearchProperties.getRerank().getFusionBeta();
    }

    @Override
    public List<ImageSearchResultDTO> search(SearchQueryDTO query, List<Float> queryVector) {
        if (query == null) {
            return List.of();
        }
        int requestTopK = Math.max(1, query.getTopK() == null ? DEFAULT_TOP_K : query.getTopK());
        int requestLimit = Math.max(1, query.getLimit() == null ? DEFAULT_RESULT_LIMIT : query.getLimit());
        boolean enableOcr = query.getEnableOcr() == null || query.getEnableOcr();
        int recallSize = resolveRecallSize(requestTopK, requestLimit);

        List<ImageSearchResultDTO> vectorHits = CollectionUtil.isEmpty(queryVector)
                ? List.of()
                : imageSearchRepository.vectorSearch(queryVector, recallSize);
        List<ImageSearchResultDTO> textHits = StringUtils.hasText(query.getKeyword())
                ? imageSearchRepository.textSearch(query.getKeyword(), recallSize, enableOcr)
                : List.of();

        List<ImageSearchResultDTO> candidates = dualRouteRrfFusionService.fuse(
                vectorHits,
                textHits,
                recallSize,
                rrfRankConstant
        );
        return applyCrossEncoderRerank(query.getKeyword(), candidates, requestLimit, enableOcr);
    }

    @Override
    public StrategyTypeEnum getType() {
        return StrategyTypeEnum.HYBRID;
    }

    private int resolveRecallSize(int requestTopK, int requestLimit) {
        int multiplier = Math.max(rrfCandidateMultiplier, 1);
        int recallByLimit = Math.max(1, requestLimit) * multiplier;
        int recallSize = Math.max(Math.max(1, requestTopK), recallByLimit);
        return Math.min(recallSize, Math.max(1, rrfMaxCandidates));
    }

    private List<ImageSearchResultDTO> applyCrossEncoderRerank(String keyword,
                                                               List<ImageSearchResultDTO> candidates,
                                                               int requestLimit,
                                                               boolean enableOcr) {
        if (CollectionUtil.isEmpty(candidates)) {
            return List.of();
        }
        int limit = Math.max(1, requestLimit);
        List<ImageSearchResultDTO> window = new ArrayList<>(candidates);
        if (!rerankEnabled || !StringUtils.hasText(keyword)) {
            return window.stream().limit(limit).collect(Collectors.toList());
        }
        int windowSize = resolveRerankWindowSize(limit, window.size());
        if (windowSize <= 0) {
            return window.stream().limit(limit).collect(Collectors.toList());
        }
        List<ImageSearchResultDTO> rerankWindow = new ArrayList<>(window.subList(0, windowSize));
        List<ImageSearchResultDTO> untouchedTail = windowSize >= window.size()
                ? List.of()
                : window.subList(windowSize, window.size());
        recordWindowMetrics(windowSize, window.size());

        List<String> docs = rerankWindow.stream()
                .map(item -> buildRerankText(item, enableOcr))
                .collect(Collectors.toList());
        Timer.Sample sample = Timer.start(meterRegistry);
        meterRegistry.counter("smartvision.search.rerank.calls").increment();

        List<RerankItem> rerankResults = searchRerankPort.rerank(keyword, docs, docs.size());
        sample.stop(Timer.builder("smartvision.search.rerank.latency")
                .description("Cross-encoder rerank latency")
                .register(meterRegistry));
        if (CollectionUtil.isEmpty(rerankResults)) {
            meterRegistry.counter("smartvision.search.rerank.fallback", "reason", "empty_result").increment();
            return window.stream().limit(limit).collect(Collectors.toList());
        }

        Map<Integer, Double> scoreByIndex = new HashMap<>();
        for (RerankItem rerankResult : rerankResults) {
            if (rerankResult == null) {
                continue;
            }
            int index = rerankResult.index();
            if (index < 0 || index >= rerankWindow.size()) {
                continue;
            }
            scoreByIndex.put(index, normalizeRerankScore(rerankResult.score()));
        }

        WeightPair weights = resolveFusionWeights();
        List<WindowRankItem> rankedWindow = buildAndSortWindow(
                rerankWindow,
                scoreByIndex,
                weights.alpha(),
                weights.beta()
        );
        List<ImageSearchResultDTO> merged = new ArrayList<>(window.size());
        for (WindowRankItem item : rankedWindow) {
            merged.add(item.dto());
        }
        merged.addAll(untouchedTail);

        List<ImageSearchResultDTO> reranked = merged.stream().limit(limit).collect(Collectors.toList());
        log.debug("Cross-encoder rerank applied in dual hybrid, candidates={}, window={}, scored={}, return={}, alpha={}, beta={}",
                window.size(), windowSize, scoreByIndex.size(), reranked.size(), weights.alpha(), weights.beta());
        return reranked;
    }

    private String buildRerankText(ImageSearchResultDTO item, boolean enableOcr) {
        if (item == null || item.getDocument() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        if (StringUtils.hasText(item.getDocument().getFileName())) {
            sb.append("filename: ").append(item.getDocument().getFileName()).append('\n');
        }
        if (!CollectionUtil.isEmpty(item.getDocument().getTags())) {
            sb.append("tags: ").append(String.join(", ", item.getDocument().getTags())).append('\n');
        }
        if (enableOcr && StringUtils.hasText(item.getDocument().getOcrContent())) {
            sb.append("ocr: ").append(item.getDocument().getOcrContent()).append('\n');
        }
        if (!CollectionUtil.isEmpty(item.getDocument().getRelations())) {
            String relations = item.getDocument().getRelations().stream()
                    .filter(Objects::nonNull)
                    .map(triple -> String.format(Locale.ROOT, "%s-%s-%s",
                            nullToEmpty(triple.getS()),
                            nullToEmpty(triple.getP()),
                            nullToEmpty(triple.getO())))
                    .collect(Collectors.joining("; "));
            if (StringUtils.hasText(relations)) {
                sb.append("relations: ").append(relations);
            }
        }
        String content = sb.toString();
        if (content.length() <= rerankMaxDocChars) {
            return content;
        }
        return content.substring(0, rerankMaxDocChars);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double normalizeRerankScore(Double score) {
        if (score == null || score <= 0) {
            return 0d;
        }
        return Math.min(score, 1d);
    }

    private int resolveRerankWindowSize(int limit, int candidateSize) {
        if (candidateSize <= 0) {
            return 0;
        }
        if (!rerankWindowEnabled) {
            return candidateSize;
        }

        int safeLimit = Math.max(1, limit);
        int base = rerankWindowSize > 0
                ? rerankWindowSize
                : safeLimit * Math.max(1, rerankWindowFactor);
        int lower = Math.max(1, rerankWindowMin);
        int upper = Math.max(lower, rerankWindowMax);
        int sized = Math.max(lower, Math.min(base, upper));
        return Math.min(candidateSize, sized);
    }

    private WeightPair resolveFusionWeights() {
        double alpha = clamp01(rerankFusionAlpha);
        double beta = clamp01(rerankFusionBeta);
        double sum = alpha + beta;
        if (sum <= 0d) {
            return new WeightPair(1d, 0d);
        }
        return new WeightPair(alpha / sum, beta / sum);
    }

    private List<WindowRankItem> buildAndSortWindow(List<ImageSearchResultDTO> rerankWindow,
                                                    Map<Integer, Double> rerankScoreByIndex,
                                                    double alpha,
                                                    double beta) {
        List<WindowRankItem> items = new ArrayList<>(rerankWindow.size());
        for (int i = 0; i < rerankWindow.size(); i++) {
            ImageSearchResultDTO dto = rerankWindow.get(i);
            double retrievalScore = normalizeRetrievalScore(dto);
            double rerankScore = rerankScoreByIndex.getOrDefault(i, 0d);
            double fusionScore = alpha * retrievalScore + beta * rerankScore;
            items.add(new WindowRankItem(i, dto, retrievalScore, rerankScore, fusionScore, extractDocId(dto)));
        }
        items.sort(Comparator
                .comparingDouble(WindowRankItem::fusionScore).reversed()
                .thenComparing(Comparator.comparingDouble(WindowRankItem::retrievalScore).reversed())
                .thenComparingLong(WindowRankItem::docId)
                .thenComparingInt(WindowRankItem::index));
        return items;
    }

    private double normalizeRetrievalScore(ImageSearchResultDTO dto) {
        if (dto == null) {
            return 0d;
        }
        Double raw = dto.getRawScore();
        if (raw != null && raw > 0d) {
            return Math.min(raw, 1d);
        }
        Double score = dto.getScore();
        if (score == null || score <= 0d) {
            return 0d;
        }
        return Math.min(score, 1d);
    }

    private long extractDocId(ImageSearchResultDTO dto) {
        if (dto == null || dto.getDocument() == null || dto.getDocument().getId() == null) {
            return Long.MAX_VALUE;
        }
        return dto.getDocument().getId();
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

    private void recordWindowMetrics(int windowSize, int candidateSize) {
        DistributionSummary.builder("smartvision.search.rerank.window.size")
                .description("Rerank window size")
                .register(meterRegistry)
                .record(windowSize);

        double ratio = candidateSize <= 0 ? 0d : ((double) windowSize) / candidateSize;
        DistributionSummary.builder("smartvision.search.rerank.window.ratio")
                .description("Rerank window size / candidate size ratio")
                .register(meterRegistry)
                .record(ratio);

        meterRegistry.counter(
                        "smartvision.search.rerank.window.hit",
                        "window_size", String.valueOf(windowSize),
                        "candidate_size", String.valueOf(candidateSize))
                .increment();
    }

    private record WeightPair(double alpha, double beta) {}

    private record WindowRankItem(int index,
                                  ImageSearchResultDTO dto,
                                  double retrievalScore,
                                  double rerankScore,
                                  double fusionScore,
                                  long docId) {
    }
}
