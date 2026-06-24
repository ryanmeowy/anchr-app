package com.anchr.core.kb.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Pure calculator for the KB health score (0–100). Encapsulates the fixed weighted
 * formula so it can be unit-tested without Spring.
 *
 * <pre>
 * score = kbStatus*35 + indexedDocumentRate*30 + indexedSegmentRate*20
 *       + freshnessScore*10 + errorPenaltyScore*5
 * score = round(max(0, min(100, score)))
 * </pre>
 */
public final class KnowledgeBaseHealthScore {

    private KnowledgeBaseHealthScore() {
    }

    public static int compute(KnowledgeBaseStatus status,
                              int documentTotal, int documentIndexed, int documentFailed,
                              int segmentTotal, int segmentIndexed,
                              LocalDateTime lastIngestedAt, LocalDateTime now) {
        double kbStatus = kbStatusFactor(status);
        double indexedDocumentRate = ratio(documentIndexed, documentTotal);
        double indexedSegmentRate = ratio(segmentIndexed, segmentTotal);
        double freshnessScore = freshnessFactor(lastIngestedAt, now);
        double errorPenaltyScore = errorPenaltyFactor(documentFailed, documentTotal);

        double score = kbStatus * 35
                + indexedDocumentRate * 30
                + indexedSegmentRate * 20
                + freshnessScore * 10
                + errorPenaltyScore * 5;

        long rounded = Math.round(score);
        return (int) Math.max(0, Math.min(100, rounded));
    }

    private static double kbStatusFactor(KnowledgeBaseStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case ACTIVE -> 1.0;
            case ARCHIVED -> 0.5;
            case DELETED -> 0.0;
        };
    }

    /** Returns numerator/denominator, or 0.0 when denominator is 0. */
    private static double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private static double freshnessFactor(LocalDateTime lastIngestedAt, LocalDateTime now) {
        if (lastIngestedAt == null || now == null) {
            return 0.0;
        }
        Duration age = Duration.between(lastIngestedAt, now);
        if (age.isNegative()) {
            // clock skew — treat as just-ingested
            return 1.0;
        }
        long hours = age.toHours();
        if (hours <= 24) {
            return 1.0;
        }
        long days = age.toDays();
        if (days <= 7) {
            return 0.8;
        }
        if (days <= 30) {
            return 0.5;
        }
        return 0.0;
    }

    private static double errorPenaltyFactor(int failedDocuments, int documentTotal) {
        if (failedDocuments <= 0) {
            return 1.0;
        }
        if (documentTotal <= 0) {
            // failed > 0 but total reported as 0 — inconsistent data; be lenient
            return 1.0;
        }
        double failedRate = (double) failedDocuments / documentTotal;
        if (failedRate <= 0.05) {
            return 0.7;
        }
        if (failedRate <= 0.10) {
            return 0.4;
        }
        return 0.0;
    }
}
