package com.anchr.core.conversation.application.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_CITATION_MARKERS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_CITATION_MARKERS_PER_PARAGRAPH;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_UNIQUE_CITATIONS;

@Component
final class AgentCitationPolicy {
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile(
            "(?:\\R\\s*){2,}|(?m)(?=^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+))");

    boolean withinLimits(String answer) {
        if (!StringUtils.hasText(answer)) return false;
        if (AgentCitationRenderer.extractSegmentIds(answer).size() > MAX_UNIQUE_CITATIONS) return false;
        if (markerCount(answer) > MAX_CITATION_MARKERS) return false;
        for (String paragraph : PARAGRAPH_BOUNDARY.split(answer)) {
            if (markerCount(paragraph) > MAX_CITATION_MARKERS_PER_PARAGRAPH) return false;
        }
        return true;
    }

    String compactMarkers(String answer) {
        if (!StringUtils.hasText(answer)) return answer;
        var matcher = AgentCitationIndexPlan.SEGMENT_MARKER.matcher(answer);
        Set<String> selectedIds = new LinkedHashSet<>();
        StringBuilder compacted = new StringBuilder();
        int totalMarkers = 0;
        int paragraphMarkers = 0;
        int previousMarkerEnd = 0;
        while (matcher.find()) {
            if (PARAGRAPH_BOUNDARY.matcher(answer.substring(previousMarkerEnd, matcher.start())).find()) {
                paragraphMarkers = 0;
            }
            String segmentId = matcher.group(1).trim();
            boolean knownId = selectedIds.contains(segmentId);
            boolean withinUniqueLimit = knownId || selectedIds.size() < MAX_UNIQUE_CITATIONS;
            boolean keep = StringUtils.hasText(segmentId)
                    && withinUniqueLimit
                    && totalMarkers < MAX_CITATION_MARKERS
                    && paragraphMarkers < MAX_CITATION_MARKERS_PER_PARAGRAPH;
            matcher.appendReplacement(compacted,
                    keep ? java.util.regex.Matcher.quoteReplacement(matcher.group()) : "");
            if (keep) {
                selectedIds.add(segmentId);
                totalMarkers++;
                paragraphMarkers++;
            }
            previousMarkerEnd = matcher.end();
        }
        matcher.appendTail(compacted);
        return compacted.toString();
    }

    private int markerCount(String value) {
        int count = 0;
        var matcher = AgentCitationIndexPlan.SEGMENT_MARKER.matcher(value == null ? "" : value);
        while (matcher.find()) count++;
        return count;
    }
}
