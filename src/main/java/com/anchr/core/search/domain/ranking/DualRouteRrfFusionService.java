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
 * Two-route (vector + text) reciprocal rank fusion service.
 * <p>
 * Different from legacy fusion, candidates and display metadata are merged from both routes.
 */
@Component
public class DualRouteRrfFusionService {

    public List<ImageSearchResultDTO> fuse(List<ImageSearchResultDTO> vectorRanking,
                                           List<ImageSearchResultDTO> textRanking,
                                           int limit,
                                           int rankConstant) {
        if (CollectionUtil.isEmpty(vectorRanking) && CollectionUtil.isEmpty(textRanking)) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        int safeRankConstant = Math.max(1, rankConstant);

        Map<Long, Accumulator> grouped = new HashMap<>();
        ingestRanking(vectorRanking, true, safeRankConstant, grouped);
        ingestRanking(textRanking, false, safeRankConstant, grouped);

        List<Accumulator> accumulators = new ArrayList<>(grouped.values());
        accumulators.sort(Comparator
                .comparingDouble(Accumulator::getRrfScore).reversed()
                .thenComparing(Comparator.comparingInt(Accumulator::getHitCount).reversed())
                .thenComparing(Comparator.comparingDouble(Accumulator::getBestRawScore).reversed())
                .thenComparingLong(Accumulator::getId));

        List<ImageSearchResultDTO> fused = new ArrayList<>(Math.min(safeLimit, accumulators.size()));
        for (Accumulator accumulator : accumulators) {
            if (fused.size() >= safeLimit) {
                break;
            }
            ImageSearchResultDTO displaySource = accumulator.resolveDisplaySource();
            if (displaySource == null || displaySource.getDocument() == null) {
                continue;
            }
            double rawScore = accumulator.bestRawScore > 0d ? accumulator.bestRawScore : accumulator.rrfScore;
            ImageSearchResultDTO textSource = accumulator.textSource;
            fused.add(ImageSearchResultDTO.builder()
                    .document(displaySource.getDocument())
                    .rawScore(rawScore)
                    .score(mapScoreToPercentage(rawScore))
                    .sortValues(displaySource.getSortValues())
                    .highlights(textSource == null ? Map.of() : textSource.getHighlights())
                    .highlightFields(textSource == null ? List.of() : textSource.getHighlightFields())
                    .vectorRecallHit(accumulator.vectorHit)
                    .textRecallHit(accumulator.textHit)
                    .build());
        }
        return fused;
    }

    private void ingestRanking(List<ImageSearchResultDTO> ranking,
                               boolean vectorRoute,
                               int safeRankConstant,
                               Map<Long, Accumulator> grouped) {
        if (CollectionUtil.isEmpty(ranking)) {
            return;
        }
        for (int i = 0; i < ranking.size(); i++) {
            ImageSearchResultDTO hit = ranking.get(i);
            ImageDocument doc = hit == null ? null : hit.getDocument();
            Long id = doc == null ? null : doc.getId();
            if (id == null) {
                continue;
            }
            Accumulator accumulator = grouped.computeIfAbsent(id, ignored -> new Accumulator(id));
            accumulator.rrfScore += reciprocalRank(safeRankConstant, i);
            accumulator.hitCount += 1;
            accumulator.captureBestScoreCandidate(hit);
            if (vectorRoute) {
                accumulator.vectorHit = true;
                if (accumulator.vectorSource == null) {
                    accumulator.vectorSource = hit;
                }
                continue;
            }
            accumulator.textHit = true;
            if (accumulator.textSource == null) {
                accumulator.textSource = hit;
            }
        }
    }

    private static double reciprocalRank(int safeRankConstant, int rankIndex) {
        return 1d / (safeRankConstant + rankIndex + 1d);
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
        private ImageSearchResultDTO vectorSource;
        private ImageSearchResultDTO textSource;
        private boolean vectorHit;
        private boolean textHit;

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

        private ImageSearchResultDTO resolveDisplaySource() {
            if (textSource != null && textSource.getDocument() != null) {
                return textSource;
            }
            if (vectorSource != null && vectorSource.getDocument() != null) {
                return vectorSource;
            }
            return primary;
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

