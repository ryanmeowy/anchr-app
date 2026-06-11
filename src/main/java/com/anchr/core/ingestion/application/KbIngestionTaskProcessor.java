package com.anchr.core.ingestion.application;

/**
 * Executes persisted knowledge base ingestion tasks.
 */
public interface KbIngestionTaskProcessor {

    void submit(String kbId, String taskId, String userId);
}
