package com.anchr.core.kb.application.ingestion;

/**
 * Executes persisted knowledge base ingestion tasks.
 */
public interface KbIngestionTaskProcessor {

    void submit(String workspaceId, String kbId, String taskId, String userId);
}
