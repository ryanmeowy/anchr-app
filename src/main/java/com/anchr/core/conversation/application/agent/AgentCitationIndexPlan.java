package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Assigns one stable, answer-order citation reference to every Agent evidence segment. */
public final class AgentCitationIndexPlan {
    static final Pattern SEGMENT_MARKER = Pattern.compile(
            "\\{\\{\\s*segment\\s*:\\s*([^{}]+?)\\s*}}", Pattern.CASE_INSENSITIVE);

    private AgentCitationIndexPlan() {
    }

    public static Map<String, AgentCitationReference> build(
            String answer,
            List<ConversationRetrievalCandidate> selectedEvidence
    ) {
        Map<String, ConversationRetrievalCandidate> evidenceBySegment = new LinkedHashMap<>();
        if (selectedEvidence != null) {
            for (ConversationRetrievalCandidate candidate : selectedEvidence) {
                if (candidate != null && StringUtils.hasText(candidate.getSegmentId())) {
                    evidenceBySegment.putIfAbsent(candidate.getSegmentId().trim(), candidate);
                }
            }
        }
        if (evidenceBySegment.isEmpty()) return Map.of();

        Map<String, Integer> assetIndexes = new LinkedHashMap<>();
        Map<String, Integer> nextSegmentIndexes = new LinkedHashMap<>();
        Map<String, AgentCitationReference> references = new LinkedHashMap<>();
        Matcher matcher = SEGMENT_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            assign(matcher.group(1).trim(), evidenceBySegment, assetIndexes, nextSegmentIndexes, references);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(references));
    }

    public static void apply(
            List<ConversationCitation> citations,
            Map<String, AgentCitationReference> references
    ) {
        if (citations == null || citations.isEmpty() || references == null || references.isEmpty()) return;
        for (ConversationCitation citation : citations) {
            if (citation == null || !StringUtils.hasText(citation.getSegmentId())) continue;
            AgentCitationReference reference = references.get(citation.getSegmentId().trim());
            if (reference == null) continue;
            citation.setAssetCitationIndex(reference.assetIndex());
            citation.setSegmentCitationIndex(reference.segmentIndex());
        }
    }

    private static void assign(
            String segmentId,
            Map<String, ConversationRetrievalCandidate> evidenceBySegment,
            Map<String, Integer> assetIndexes,
            Map<String, Integer> nextSegmentIndexes,
            Map<String, AgentCitationReference> references
    ) {
        if (!StringUtils.hasText(segmentId) || references.containsKey(segmentId)) return;
        ConversationRetrievalCandidate candidate = evidenceBySegment.get(segmentId);
        if (candidate == null) return;
        String assetKey = StringUtils.hasText(candidate.getAssetId())
                ? "asset:" + candidate.getAssetId().trim()
                : "segment:" + segmentId;
        int assetIndex = assetIndexes.computeIfAbsent(assetKey, ignored -> assetIndexes.size() + 1);
        int segmentIndex = nextSegmentIndexes.merge(assetKey, 1, Integer::sum);
        references.put(segmentId, new AgentCitationReference(assetIndex, segmentIndex, segmentId));
    }
}
