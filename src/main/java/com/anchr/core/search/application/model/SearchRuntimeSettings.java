package com.anchr.core.search.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;

public record SearchRuntimeSettings(
        int rankConstant,
        int candidateMultiplier,
        int maxCandidates,
        int textTopK,
        int documentImageTopK,
        float textSimilarity,
        float documentImageSimilarity,
        int maxDocChars,
        boolean windowEnabled,
        int windowSize,
        int windowFactor,
        int windowMin,
        int windowMax,
        double fusionAlpha,
        double fusionBeta
) {
    public static SearchRuntimeSettings load(RuntimeConfigUnit unit) {
        return new SearchRuntimeSettings(
                unit.getInt("SEARCH", "rankConstant", 60),
                unit.getInt("SEARCH", "candidateMultiplier", 4),
                unit.getInt("SEARCH", "maxCandidates", 200),
                unit.getInt("SEARCH", "textTopK", 80),
                unit.getInt("SEARCH", "documentImageTopK", 40),
                unit.getFloat("SEARCH", "textSimilarity", 0.75F),
                unit.getFloat("SEARCH", "documentImageSimilarity", 0.70F),
                unit.getInt("SEARCH", "maxDocChars", 1200),
                unit.getBoolean("SEARCH", "windowEnabled", true),
                unit.getInt("SEARCH", "windowSize", 40),
                unit.getInt("SEARCH", "windowFactor", 3),
                unit.getInt("SEARCH", "windowMin", 20),
                unit.getInt("SEARCH", "windowMax", 80),
                unit.getDouble("SEARCH", "fusionAlpha", 0.6D),
                unit.getDouble("SEARCH", "fusionBeta", 0.4D));
    }

    public static SearchRuntimeSettings defaults() {
        return new SearchRuntimeSettings(
                60, 4, 200, 80, 40, 0.75F, 0.70F,
                1200, true, 40, 3, 20, 80, 0.6D, 0.4D);
    }
}
