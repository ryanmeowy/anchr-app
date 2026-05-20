package com.anchr.core.search.domain.util;
/**
 * Utility class for score formatting.
 *
 * @author Ryan
 * @since 2025/12/29
 */
public class ScoreUtil {

    /**
     * Clamp the raw ES score into [0, 1] as a lightweight display score.
     *
     * @param rawScore original score (ES score)
     * @return score in [0, 1]
     */
    public static double mapScoreToPercentage(Double rawScore) {
        if (rawScore == null || rawScore <= 0) {
            return 0;
        }
        return rawScore / (0.3d + rawScore);
    }

    /**
     * Keep similar-search score intuitive: high-confidence matches can reach 1.0.
     *
     * @param rawScore original score (ES score)
     * @return score in [0, 1]
     */
    public static double mapScoreForSimilar(Double rawScore) {
        if (rawScore == null || rawScore <= 0) {
            return 0;
        }
        return Math.min(1.0d, rawScore);
    }
}
