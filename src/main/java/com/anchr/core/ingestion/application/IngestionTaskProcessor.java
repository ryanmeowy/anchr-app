package com.anchr.core.ingestion.application;

/**
 * Executes persisted knowledge base ingestion tasks.
 */
public interface IngestionTaskProcessor {

    void submit(String kbId, String taskId, String userId);
}
