package com.anchr.core.conversation.application.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal inspection audit; serialized only at persistence and diagnostic boundaries. */
public record EvidenceCheckReport(
        int version, boolean success, String failureReason, String errorType,
        String inputFingerprint, String cleanedFingerprint, int paragraphCount, int calls,
        long keep, long isolate, long uncertain, int promptTokens, int completionTokens,
        String modelProfile, Set<String> modelNames, long latencyMs,
        List<Decision> decisions, List<Unit> paragraphs, List<Field> fields,
        List<Map<String, String>> sources, List<Integer> requestChars, int outputTokenReserve) {
    public record Decision(String id, String decision, String reason) {}
    public record Range(int start, int end) {}
    public record Unit(String id, String fingerprint, List<Range> originalRanges) {}
    public record Field(String path, List<String> paragraphIds) {}

    public boolean capacityExceeded() { return "evidence_check_capacity_exceeded".equals(failureReason); }

    public static EvidenceCheckReport failed(String reason) {
        return new EvidenceCheckReport(2, false, reason, "", "", "", 0, 0, 0, 0, 0, 0, 0,
                "conversation_generation", Set.of(), 0, List.of(), List.of(), List.of(), List.of(), List.of(), 8192);
    }
}
