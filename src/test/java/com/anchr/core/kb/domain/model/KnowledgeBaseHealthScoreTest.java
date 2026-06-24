package com.anchr.core.kb.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseHealthScoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 24, 12, 0, 0);

    @Test
    void activeFullyIndexedFreshNoFailures_shouldScore100() {
        // 1*35 + 1*30 + 1*20 + 1*10 + 1*5 = 100
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 100, 0, 1000, 1000, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(100);
    }

    @Test
    void archivedStatus_halvesTheStatusComponent() {
        // 0.5*35 + 1*30 + 1*20 + 1*10 + 1*5 = 82.5 -> 83 (round-half-up of 82.5 = 83)
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ARCHIVED, 10, 10, 0, 100, 100, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(83);
    }

    @Test
    void deletedStatus_scoresZeroFromStatusButStillAccumulatesOthers() {
        // 0*35 + 1*30 + 1*20 + 1*10 + 1*5 = 65
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.DELETED, 10, 10, 0, 100, 100, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(65);
    }

    @Test
    void emptyKb_ratesAreZero_scoreStillReflectsStatusFreshnessError() {
        // 1*35 + 0*30 + 0*20 + 1*10 (fresh) + 1*5 (no failures) = 50
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 0, 0, 0, 0, 0, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(50);
    }

    @Test
    void emptyKbWithNullLastIngestedAt_freshnessIsZero() {
        // 1*35 + 0 + 0 + 0 + 1*5 = 40
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 0, 0, 0, 0, 0, null, NOW);
        assertThat(score).isEqualTo(40);
    }

    @Test
    void partialIndexing_scalesDocumentAndSegmentComponents() {
        // 50 docs: 25 indexed -> docRate 0.5; 100 segs: 50 indexed -> segRate 0.5
        // 1*35 + 0.5*30 + 0.5*20 + 1*10 + 1*5 = 75
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 50, 25, 0, 100, 50, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(75);
    }

    @Test
    void freshnessBands() {
        // <=24h -> 1.0 ; fully indexed
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 10, 10, 0, 100, 100, NOW.minusHours(24), NOW)).isEqualTo(100);
        // <=7d -> 0.8
        // 35 + 30 + 20 + 0.8*10 + 5 = 98
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 10, 10, 0, 100, 100, NOW.minusDays(7), NOW)).isEqualTo(98);
        // <=30d -> 0.5
        // 35 + 30 + 20 + 5 + 5 = 95
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 10, 10, 0, 100, 100, NOW.minusDays(30), NOW)).isEqualTo(95);
        // >30d -> 0.0
        // 35 + 30 + 20 + 0 + 5 = 90
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 10, 10, 0, 100, 100, NOW.minusDays(31), NOW)).isEqualTo(90);
    }

    @Test
    void errorPenaltyBands() {
        // 0% failed -> 1.0 ; 100 docs all indexed
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 100, 0, 1000, 1000, NOW.minusHours(1), NOW)).isEqualTo(100);
        // 5% failed (5/100), 95 indexed -> docRate 0.95 ; errorPenalty 0.7
        // 35 + 0.95*30 + 20 + 10 + 0.7*5 = 35 + 28.5 + 20 + 10 + 3.5 = 97
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 95, 5, 1000, 1000, NOW.minusHours(1), NOW)).isEqualTo(97);
        // 10% failed (10/100), 90 indexed -> docRate 0.9 ; errorPenalty 0.4
        // 35 + 0.9*30 + 20 + 10 + 0.4*5 = 35 + 27 + 20 + 10 + 2 = 94
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 90, 10, 1000, 1000, NOW.minusHours(1), NOW)).isEqualTo(94);
        // >10% failed (20/100), 80 indexed -> docRate 0.8 ; errorPenalty 0.0
        // 35 + 0.8*30 + 20 + 10 + 0 = 35 + 24 + 20 + 10 = 89
        assertThat(KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 80, 20, 1000, 1000, NOW.minusHours(1), NOW)).isEqualTo(89);
    }

    @Test
    void allFailed_errorPenaltyZero() {
        // 100 docs, 0 indexed, 100 failed -> docRate 0, segRate 0, errorPenalty 0
        // 35 + 0 + 0 + 10 + 0 = 45
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 0, 100, 1000, 0, NOW.minusHours(1), NOW);
        assertThat(score).isEqualTo(45);
    }

    @Test
    void clampsToLowerBound() {
        // deleted status (0) + nothing indexed + null freshness + >10% failed (errorPenalty 0) -> 0
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.DELETED, 100, 0, 100, 1000, 0, null, NOW);
        assertThat(score).isEqualTo(0);
    }

    @Test
    void neverExceeds100() {
        // already 100 at the ceiling; ensure rounding doesn't push over
        int score = KnowledgeBaseHealthScore.compute(
                KnowledgeBaseStatus.ACTIVE, 100, 100, 0, 1000, 1000, NOW, NOW);
        assertThat(score).isLessThanOrEqualTo(100);
    }
}
