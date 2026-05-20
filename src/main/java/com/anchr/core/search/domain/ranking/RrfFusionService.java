package com.anchr.core.search.domain.ranking;

import cn.hutool.core.collection.CollectionUtil;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.anchr.core.search.domain.util.ScoreUtil.mapScoreToPercentage;

/**
 * Reciprocal Rank Fusion for multi-retriever result merging.
 */
@Component
public class RrfFusionService {

    public List<ImageSearchResultDTO> fuse(List<List<ImageSearchResultDTO>> rankingLists,
                                           int limit,
                                           int rankConstant) {
        if (CollectionUtil.isEmpty(rankingLists)) {
            return List.of();
        }
        List<ImageSearchResultDTO> hybridRanking = rankingLists.getFirst();
        if (CollectionUtil.isEmpty(hybridRanking)) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        int safeRankConstant = Math.max(1, rankConstant);

        // First list (hybrid) is the only candidate source and metadata source.
        // Other lists contribute ranking signal only.
        Map<Long, Accumulator> grouped = new HashMap<>();
        for (int i = 0; i < hybridRanking.size(); i++) {
            ImageSearchResultDTO hit = hybridRanking.get(i);
            ImageDocument doc = hit == null ? null : hit.getDocument();
            Long id = doc == null ? null : doc.getId();
            if (id == null) {
                continue;
            }
            Accumulator accumulator = grouped.computeIfAbsent(id, ignored -> new Accumulator(id));
            accumulator.hybridSource = hit;
            double reciprocal = 1d / (safeRankConstant + i + 1d);
            accumulator.rrfScore += reciprocal;
            accumulator.hitCount += 1;
            accumulator.captureBestScoreCandidate(hit);
        }

        for (int rankingListIndex = 1; rankingListIndex < rankingLists.size(); rankingListIndex++) {
            List<ImageSearchResultDTO> rankingList = rankingLists.get(rankingListIndex);
            if (CollectionUtil.isEmpty(rankingList)) {
                continue;
            }
            for (int i = 0; i < rankingList.size(); i++) {
                ImageSearchResultDTO hit = rankingList.get(i);
                ImageDocument doc = hit == null ? null : hit.getDocument();
                Long id = doc == null ? null : doc.getId();
                if (id == null) {
                    continue;
                }
                Accumulator accumulator = grouped.get(id);
                if (accumulator == null) {
                    continue;
                }
                double reciprocal = 1d / (safeRankConstant + i + 1d);
                accumulator.rrfScore += reciprocal;
                accumulator.hitCount += 1;
                accumulator.captureBestScoreCandidate(hit);
            }
        }

        List<Accumulator> accumulators = new ArrayList<>(grouped.values());
        accumulators.sort(
                Comparator.comparingDouble(Accumulator::getRrfScore).reversed()
                        .thenComparing(Comparator.comparingInt(Accumulator::getHitCount).reversed())
                        .thenComparing(Comparator.comparingDouble(Accumulator::getBestRawScore).reversed())
                        .thenComparingLong(Accumulator::getId)
        );

        List<ImageSearchResultDTO> fused = new ArrayList<>(Math.min(safeLimit, accumulators.size()));
        for (Accumulator accumulator : accumulators) {
            if (fused.size() >= safeLimit) {
                break;
            }
            if (accumulator.hybridSource == null || accumulator.hybridSource.getDocument() == null) {
                continue;
            }
            double rawScore = accumulator.bestRawScore > 0 ? accumulator.bestRawScore : accumulator.rrfScore;
            fused.add(ImageSearchResultDTO.builder()
                    .document(accumulator.hybridSource.getDocument())
                    .rawScore(rawScore)
                    .score(mapScoreToPercentage(rawScore))
                    .sortValues(accumulator.hybridSource.getSortValues())
                    .highlights(accumulator.hybridSource.getHighlights())
                    .highlightFields(accumulator.hybridSource.getHighlightFields())
                    .vectorRecallHit(accumulator.hybridSource.getVectorRecallHit())
                    .textRecallHit(accumulator.hybridSource.getTextRecallHit())
                    .build());
        }
        return fused;
    }

    private static double decisionScore(ImageSearchResultDTO result) {
        if (result == null) {
            return 0d;
        }
        if (result.getRawScore() != null) {
            return result.getRawScore();
        }
        if (result.getScore() != null) {
            return result.getScore();
        }
        return 0d;
    }

    private static final class Accumulator {
        private final long id;
        private double rrfScore;
        private int hitCount;
        private double bestRawScore;
        private ImageSearchResultDTO primary;
        private ImageSearchResultDTO hybridSource;

        private Accumulator(long id) {
            this.id = id;
        }

        private void captureBestScoreCandidate(ImageSearchResultDTO hit) {
            if (hit == null) {
                return;
            }
            double currentDecisionScore = decisionScore(hit);
            if (primary == null || currentDecisionScore > bestRawScore) {
                primary = hit;
                bestRawScore = currentDecisionScore;
            }
        }

        private long getId() {
            return id;
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
}
