package com.anchr.core.settings.domain.model;

public enum SearchRuntimeConfigKey implements RuntimeConfigKey {
    RANK_CONSTANT("rankConstant"),
    CANDIDATE_MULTIPLIER("candidateMultiplier"),
    MAX_CANDIDATES("maxCandidates"),
    TEXT_TOP_K("textTopK"),
    DOCUMENT_IMAGE_TOP_K("documentImageTopK"),
    TEXT_SIMILARITY("textSimilarity"),
    DOCUMENT_IMAGE_SIMILARITY("documentImageSimilarity"),
    MAX_DOC_CHARS("maxDocChars"),
    WINDOW_ENABLED("windowEnabled"),
    WINDOW_SIZE("windowSize"),
    WINDOW_FACTOR("windowFactor"),
    WINDOW_MIN("windowMin"),
    WINDOW_MAX("windowMax"),
    FUSION_ALPHA("fusionAlpha"),
    FUSION_BETA("fusionBeta");

    private final String propertyName;

    SearchRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.SEARCH;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
