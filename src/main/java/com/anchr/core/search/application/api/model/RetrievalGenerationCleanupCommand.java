package com.anchr.core.search.application.api.model;

public record RetrievalGenerationCleanupCommand(String kbId, String assetId, long generation) {
}
