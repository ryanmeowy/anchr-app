package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.springframework.util.StringUtils;

import java.util.*;

/** Limits only traditional answer inputs; never changes retrieval or Agent policy. */
public final class TraditionalRagEvidencePolicy {
    public static final int MAX_ASSETS = 5;
    public static final int MAX_PER_ASSET = 3;
    public static final int MAX_SEGMENTS = 10;
    public static final int MAX_CONTEXT_CHARS = 24_000;

    private TraditionalRagEvidencePolicy() {}

    public record Selection(List<ConversationRetrievalCandidate> candidates, String context,
                            Map<String, Integer> excluded) {
        public int contextChars() { return context.codePointCount(0, context.length()); }
        public boolean budgetExceeded() {
            return candidates.isEmpty() && excluded.getOrDefault("budget", 0) > 0;
        }
    }

    public static Selection select(List<ConversationRetrievalCandidate> candidates) {
        List<ConversationRetrievalCandidate> selected = new ArrayList<>();
        Set<String> segmentIds = new HashSet<>();
        Map<String, Set<String>> bodies = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (ConversationRetrievalCandidate candidate : candidates == null
                ? List.<ConversationRetrievalCandidate>of() : candidates) {
            if (candidate == null || !candidate.isCitableEvidence()
                    || !StringUtils.hasText(candidate.getAssetId())
                    || !StringUtils.hasText(candidate.getSegmentId())
                    || !StringUtils.hasText(body(candidate))) {
                increment(excluded, "ineligible");
                continue;
            }
            String asset = candidate.getAssetId().trim();
            if (!segmentIds.add(candidate.getSegmentId().trim())
                    || !bodies.computeIfAbsent(asset, ignored -> new HashSet<>()).add(body(candidate))) {
                increment(excluded, "duplicate");
                continue;
            }
            String rejection = selected.size() >= MAX_SEGMENTS ? "total_limit"
                    : !counts.containsKey(asset) && counts.size() >= MAX_ASSETS ? "asset_limit"
                    : counts.getOrDefault(asset, 0) >= MAX_PER_ASSET ? "per_asset_limit" : null;
            if (rejection != null) {
                increment(excluded, rejection);
                continue;
            }
            List<ConversationRetrievalCandidate> proposed = new ArrayList<>(selected);
            proposed.add(candidate);
            String context = render(group(proposed));
            if (context.codePointCount(0, context.length()) > MAX_CONTEXT_CHARS) {
                increment(excluded, "budget");
                continue;
            }
            selected.add(candidate);
            counts.merge(asset, 1, Integer::sum);
        }
        List<ConversationRetrievalCandidate> grouped = group(selected);
        return new Selection(grouped, render(grouped), Collections.unmodifiableMap(excluded));
    }

    private static void increment(Map<String, Integer> counts, String reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private static List<ConversationRetrievalCandidate> group(List<ConversationRetrievalCandidate> candidates) {
        Map<String, List<ConversationRetrievalCandidate>> groups = new LinkedHashMap<>();
        candidates.forEach(candidate -> groups.computeIfAbsent(candidate.getAssetId().trim(),
                ignored -> new ArrayList<>()).add(candidate));
        List<ConversationRetrievalCandidate> result = new ArrayList<>();
        for (List<ConversationRetrievalCandidate> group : groups.values()) {
            // If any position is unknown, retain retrieval order for the whole group.
            if (group.stream().allMatch(c -> c.getAnchor() != null && c.getAnchor().getChunkOrder() != null)) {
                group.sort(Comparator.comparing(c -> c.getAnchor().getChunkOrder()));
            } else if (group.stream().allMatch(c -> c.getPageNo() != null)) {
                group.sort(Comparator.comparing(ConversationRetrievalCandidate::getPageNo));
            }
            result.addAll(group);
        }
        return List.copyOf(result);
    }

    public static String body(ConversationRetrievalCandidate candidate) {
        return StringUtils.hasText(candidate.getContent()) ? candidate.getContent().trim()
                : candidate.getSnippet() == null ? "" : candidate.getSnippet().trim();
    }

    /** The exact evidence region inserted into the prompt, including all metadata and delimiters. */
    public static String render(List<ConversationRetrievalCandidate> candidates) {
        StringBuilder result = new StringBuilder();
        var citations = new ConversationCitationMapper().mapFromSearchResults(candidates);
        String previousAsset = null;
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            var citation = citations.get(i);
            if (!Objects.equals(previousAsset, candidate.getAssetId())) {
                result.append("\n文档 asset=").append(candidate.getAssetId())
                        .append(",file=").append(Objects.toString(citation.getFileName(), "NA")).append('\n');
                previousAsset = candidate.getAssetId();
            }
            result.append('[').append(i + 1).append("] segmentId=").append(candidate.getSegmentId())
                    .append(",page=").append(Objects.toString(citation.getPageNo(), "NA"))
                    .append(",section=").append(Objects.toString(citation.getTitle(), "NA"))
                    .append(",type=").append(Objects.toString(citation.getHitType(), "NA"))
                    .append("\n正文：\n").append(body(candidate)).append("\n片段结束\n");
        }
        return result.toString();
    }
}
