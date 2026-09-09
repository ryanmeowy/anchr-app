package com.anchr.core.conversation.application.agent;

/** Counts for one inspected tool result; citation counts belong to answer finalization. */
public record EvidenceStatistics(int originalEvidenceCount, int evidenceCount, int segmentCount, int documentCount) {}
