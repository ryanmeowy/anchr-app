package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps segment ids as an internal evidence protocol while rendering hierarchical Agent citations.
 */
public final class AgentCitationRenderer {
    private AgentCitationRenderer() {
    }

    public static List<String> extractSegmentIds(String answer) {
        if (!StringUtils.hasText(answer)) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = AgentCitationIndexPlan.SEGMENT_MARKER.matcher(answer);
        while (matcher.find()) {
            String id = matcher.group(1).trim();
            if (StringUtils.hasText(id)) ids.add(id);
        }
        return List.copyOf(ids);
    }

    public static AgentCitationRenderResult render(
            String answer,
            List<ConversationRetrievalCandidate> selectedEvidence
    ) {
        return render(answer, selectedEvidence, null);
    }

    static AgentCitationRenderResult render(
            String answer,
            List<ConversationRetrievalCandidate> selectedEvidence,
            Map<String, AgentCitationReference> fixedReferences
    ) {
        String source = answer == null ? "" : answer.trim();
        List<ConversationRetrievalCandidate> evidence = selectedEvidence == null
                ? List.of() : selectedEvidence;
        Map<String, ConversationRetrievalCandidate> evidenceBySegment = new LinkedHashMap<>();
        for (ConversationRetrievalCandidate candidate : evidence) {
            if (candidate != null && StringUtils.hasText(candidate.getSegmentId())) {
                evidenceBySegment.putIfAbsent(candidate.getSegmentId().trim(), candidate);
            }
        }
        if (evidenceBySegment.isEmpty()) return new AgentCitationRenderResult(source, Map.of());
        Map<String, AgentCitationReference> references = fixedReferences == null
                ? AgentCitationIndexPlan.build(source, evidence)
                : fixedReferences;

        Matcher matcher = AgentCitationIndexPlan.SEGMENT_MARKER.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String segmentId = matcher.group(1).trim();
            AgentCitationReference reference = references.get(segmentId);
            String replacement = reference == null ? "" : "[" + reference.label() + "]";
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);

        String value = rendered.toString();
        // A model may mention a known id outside the marker syntax. Never expose that internal identifier.
        List<String> rawIds = evidenceBySegment.keySet().stream()
                .sorted(Comparator.comparingInt(String::length)
                        .reversed())
                .toList();
        for (String segmentId : rawIds) {
            value = value.replace(segmentId, "");
        }
        value = cleanAfterRemoval(value);
        return new AgentCitationRenderResult(value.trim(), references);
    }

    static boolean containsAuthoredVisibleCitation(
            String answer,
            Map<String, AgentCitationReference> references
    ) {
        if (!StringUtils.hasText(answer) || references == null || references.isEmpty()) return false;
        Set<String> labels = references.values().stream()
                .map(AgentCitationReference::label)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String label : labels) {
            Pattern authoredLabel = Pattern.compile(
                    "(?<![A-Za-z0-9_])\\[" + Pattern.quote(label) + "](?!\\s*\\()"
            );
            if (authoredLabel.matcher(answer).find()) return true;
        }
        return false;
    }

    private static String cleanAfterRemoval(String value) {
        return value.replaceAll("[ \\t]+([，。；：,.!?])", "$1");
    }
}
