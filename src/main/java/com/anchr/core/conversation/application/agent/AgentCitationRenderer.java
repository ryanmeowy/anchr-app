package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps segment ids as an internal evidence protocol while rendering user-visible citations by asset.
 */
public final class AgentCitationRenderer {
    private static final Pattern SEGMENT_MARKER = Pattern.compile(
            "\\{\\{\\s*segment\\s*:\\s*([^{}]+?)\\s*}}", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_NUMERIC_CITATION = Pattern.compile("\\[\\d+]");

    private AgentCitationRenderer() {
    }

    public static List<String> extractSegmentIds(String answer) {
        if (!StringUtils.hasText(answer)) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = SEGMENT_MARKER.matcher(answer);
        while (matcher.find()) {
            String id = matcher.group(1).trim();
            if (StringUtils.hasText(id)) ids.add(id);
        }
        return List.copyOf(ids);
    }

    public static String render(String answer, List<ConversationRetrievalCandidate> selectedEvidence) {
        String source = answer == null ? "" : answer.trim();
        List<ConversationRetrievalCandidate> evidence = selectedEvidence == null
                ? List.of() : selectedEvidence;

        Map<String, Integer> documentIndexes = new LinkedHashMap<>();
        Map<String, Integer> segmentIndexes = new LinkedHashMap<>();
        for (ConversationRetrievalCandidate candidate : evidence) {
            if (candidate == null || !StringUtils.hasText(candidate.getSegmentId())) continue;
            String segmentId = candidate.getSegmentId().trim();
            String documentKey = StringUtils.hasText(candidate.getAssetId())
                    ? "asset:" + candidate.getAssetId().trim()
                    : "segment:" + segmentId;
            int documentIndex = documentIndexes.computeIfAbsent(
                    documentKey, ignored -> documentIndexes.size() + 1);
            segmentIndexes.putIfAbsent(segmentId, documentIndex);
        }
        if (segmentIndexes.isEmpty()) return source;

        // Numeric references authored directly by the model are not trusted: only internal segment markers
        // are allowed to produce clickable citation indexes.
        String withoutModelIndexes = MODEL_NUMERIC_CITATION.matcher(source).replaceAll("");
        Matcher matcher = SEGMENT_MARKER.matcher(withoutModelIndexes);
        StringBuilder rendered = new StringBuilder();
        boolean validMarkerFound = false;
        while (matcher.find()) {
            Integer index = segmentIndexes.get(matcher.group(1).trim());
            String replacement = index == null ? "" : "[" + index + "]";
            if (index != null) validMarkerFound = true;
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);

        String value = rendered.toString();
        // A model may mention a known id outside the marker syntax. Never expose that internal identifier.
        List<Map.Entry<String, Integer>> rawIds = segmentIndexes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getKey().length())
                        .reversed())
                .toList();
        for (Map.Entry<String, Integer> entry : rawIds) {
            value = value.replace(entry.getKey(), "[" + entry.getValue() + "]");
        }
        value = cleanAfterRemoval(value);

        if (!segmentIndexes.isEmpty() && !validMarkerFound) {
            List<String> indexes = new ArrayList<>();
            new LinkedHashSet<>(documentIndexes.values())
                    .forEach(index -> indexes.add("[" + index + "]"));
            value = value + "\n\n" + String.join(" ", indexes);
        }
        return value.trim();
    }

    private static String cleanAfterRemoval(String value) {
        return value.replaceAll("[ \\t]+([，。；：,.!?])", "$1");
    }
}
