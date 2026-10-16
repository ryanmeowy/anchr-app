package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
final class AgentAnswerVerifier {
    private static final Pattern AUTHORED_VISIBLE_CITATION =
            Pattern.compile("(?<![A-Za-z0-9_])\\[(?:\\d+)(?:-\\d+)?](?!\\s*\\()");
    private static final Pattern VISIBLE_CITATION =
            Pattern.compile("(?<![A-Za-z0-9_])\\[(\\d+)(?:-(\\d+))?](?!\\s*\\()");
    private final ConversationCitationMapper citationMapper;
    private final AgentCitationPolicy citationPolicy;

    AgentAnswerVerifier(ConversationCitationMapper citationMapper,
                        AgentCitationPolicy citationPolicy) {
        this.citationMapper = citationMapper;
        this.citationPolicy = citationPolicy;
    }

    AgentAnswerValidationOutcome verify(AgentState state,
                                        AgentFinalAnswer answer) {
        return verify(state, answer, state.evidence().values(), false);
    }

    AgentAnswerValidationOutcome verifyEvidenceFinalizer(
            AgentState state,
            AgentFinalAnswer answer,
            Collection<ConversationRetrievalCandidate> allowedEvidence
    ) {
        return verify(state, answer, allowedEvidence, true);
    }

    private AgentAnswerValidationOutcome verify(
            AgentState state,
            AgentFinalAnswer answer,
            Collection<ConversationRetrievalCandidate> allowedEvidence,
            boolean evidenceOnly
    ) {
        return verify(state, answer, allowedEvidence, evidenceOnly, true);
    }

    private AgentAnswerValidationOutcome verify(
            AgentState state,
            AgentFinalAnswer answer,
            Collection<ConversationRetrievalCandidate> allowedEvidence,
            boolean evidenceOnly,
            boolean allowRepair
    ) {
        if (answer == null || answer.answerType() == null || !StringUtils.hasText(answer.answer())) {
            return rejected("INVALID_FINAL_ANSWER",
                    "必须通过 deliver_answer 提交非空回答，并明确填写 answerType",
                    "invalid_agent_final_answer");
        }
        List<String> requested = answer.citedSegmentIds() == null
                ? List.of() : answer.citedSegmentIds();
        List<String> markers = AgentCitationRenderer.extractSegmentIds(answer.answer());
        if (answer.answerType() == AgentAnswerType.NO_EVIDENCE) {
            if (!requested.isEmpty() || !markers.isEmpty()) {
                return rejected("UNEXPECTED_NO_EVIDENCE_CITATION",
                        "NO_EVIDENCE 不得携带知识引用；不要用无关片段证明资料未提及",
                        "invalid_no_evidence_citation");
            }
            return verified(new VerifiedNoEvidenceAnswer(noEvidenceAnswer(state)));
        }
        if (evidenceOnly && answer.answerType() != AgentAnswerType.KNOWLEDGE) {
            return rejected("INVALID_FINAL_ANSWER",
                    "证据回答只能提交 KNOWLEDGE 或 NO_EVIDENCE",
                    "invalid_agent_final_answer");
        }
        if (answer.answerType() != AgentAnswerType.KNOWLEDGE) {
            if (!requested.isEmpty() || !markers.isEmpty()) {
                return rejected("UNEXPECTED_CITATION",
                        "CHAT、CLARIFICATION 和 NO_EVIDENCE 不得携带知识引用；证据直接支持核心答案时必须改用 KNOWLEDGE",
                        "unexpected_agent_citation");
            }
            return verified(new VerifiedPlainAnswer(answer.answer().trim()));
        }

        Map<String, ConversationRetrievalCandidate> allowedById = new LinkedHashMap<>();
        if (allowedEvidence != null) {
            for (ConversationRetrievalCandidate candidate : allowedEvidence) {
                if (candidate != null && StringUtils.hasText(candidate.getSegmentId())) {
                    allowedById.putIfAbsent(candidate.getSegmentId().trim(), candidate);
                }
            }
        }
        if (allowedById.isEmpty()) {
            return rejected("GROUNDING_REQUIRED",
                    "KNOWLEDGE 回答缺少当前 Run 的证据，请先调用 search_knowledge、read_document 或 find_documents",
                    "missing_agent_evidence");
        }
        Set<String> requestedSet = requested.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ConversationRetrievalCandidate> selected = requestedSet.stream()
                .map(allowedById::get).filter(Objects::nonNull).toList();
        if (AUTHORED_VISIBLE_CITATION.matcher(answer.answer()).find()) {
            AgentFinalAnswer normalized = allowRepair
                    ? repairVisibleCitations(answer, requested, selected) : null;
            if (normalized != null) {
                log.info("Agent visible citations normalized before validation, kept={}",
                        normalized.citedSegmentIds());
                return verify(state, normalized, allowedEvidence, evidenceOnly, false);
            }
            return rejected("UNTRUSTED_VISIBLE_CITATION",
                    "不要自行生成数字引用；只使用当前证据的 {{segment:实际ID}} Marker",
                    "untrusted_visible_citation");
        }
        Set<String> markerSet = new LinkedHashSet<>(markers);
        List<String> illegal = requestedSet.stream()
                .filter(id -> !allowedById.containsKey(id)).toList();
        boolean containsBlankRequest = requested.stream().anyMatch(id -> !StringUtils.hasText(id));
        if (!illegal.isEmpty() || requestedSet.isEmpty() || containsBlankRequest) {
            return rejected("INVALID_CITATION",
                    illegal.isEmpty() ? "KNOWLEDGE 回答必须引用本轮证据" : "引用不属于本轮证据: " + illegal,
                    "invalid_agent_citation");
        }
        if (!requestedSet.isEmpty() && markerSet.isEmpty()) {
            return rejected("MISSING_CITATION_MARKER",
                    "KNOWLEDGE 回答必须把最直接的证据 Marker 放在对应结论之后；不要只填写 citedSegmentIds",
                    "missing_agent_citation_marker");
        }
        if (!requestedSet.equals(markerSet)) {
            AgentFinalAnswer normalized = allowRepair ? repairCitationBinding(answer, allowedById.keySet()) : null;
            if (normalized != null) {
                log.info("Agent citation binding normalized before validation, declared={}, markers={}, kept={}",
                        requestedSet.size(), markerSet.size(), normalized.citedSegmentIds());
                return verify(state, normalized, allowedEvidence, evidenceOnly, false);
            }
            return rejected("CITATION_BINDING_MISMATCH",
                    "citedSegmentIds 必须与 answer 中实际出现的证据 Marker 一一对应",
                    "invalid_agent_citation_binding");
        }
        if (!citationPolicy.withinLimits(answer.answer())) {
            AgentFinalAnswer compacted = allowRepair ? repairCitationDensity(answer) : null;
            if (compacted != null) {
                log.info("Agent citation density compacted before validation, kept={}", compacted.citedSegmentIds());
                return verify(state, compacted, allowedEvidence, evidenceOnly, false);
            }
            return rejected("CITATION_DENSITY_EXCEEDED",
                    "引用超过限制：全文最多 10 个不同引用、12 个 Marker，每段最多 3 个 Marker",
                    "agent_citation_density_exceeded");
        }
        AgentCitationRenderResult rendered = AgentCitationRenderer.render(answer.answer(), selected);
        List<ConversationRetrievalCandidate> citedEvidence = selected.stream()
                .filter(candidate -> rendered.references().containsKey(candidate.getSegmentId()))
                .toList();
        List<ConversationCitation> citations = citationMapper.mapFromSearchResults(citedEvidence);
        AgentCitationIndexPlan.apply(citations, rendered.references());
        return verified(new VerifiedCitedAnswer(rendered.answer(), citations, citedEvidence));
    }

    /**
     * Rewrites model-authored visible labels such as {@code [1-2]} back into the internal segment markers when
     * the declared evidence order reproduces those exact labels. The mapping is accepted only when rendering the
     * rewritten answer yields the same labels in the same order, so a guessed or inconsistent numbering is still
     * rejected instead of being bound to the wrong segment.
     */
    private AgentFinalAnswer repairVisibleCitations(AgentFinalAnswer answer,
                                                    List<String> requested,
                                                    List<ConversationRetrievalCandidate> selected) {
        String text = answer.answer();
        List<String> labels = new ArrayList<>();
        List<String> distinct = new ArrayList<>();
        Matcher matcher = VISIBLE_CITATION.matcher(text);
        while (matcher.find()) {
            String label = visibleLabel(matcher);
            labels.add(label);
            if (!distinct.contains(label)) distinct.add(label);
        }
        List<String> declared = requested.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (distinct.isEmpty() || distinct.size() != declared.size()) return null;

        Map<String, String> segmentByLabel = new LinkedHashMap<>();
        for (int index = 0; index < distinct.size(); index++) {
            segmentByLabel.put(distinct.get(index), declared.get(index));
        }
        StringBuilder rewritten = new StringBuilder();
        Matcher occurrence = VISIBLE_CITATION.matcher(text);
        while (occurrence.find()) {
            String segmentId = segmentByLabel.get(visibleLabel(occurrence));
            occurrence.appendReplacement(rewritten,
                    Matcher.quoteReplacement("{{segment:" + segmentId + "}}"));
        }
        occurrence.appendTail(rewritten);
        String candidate = rewritten.toString();

        AgentCitationRenderResult rendered = AgentCitationRenderer.render(candidate, selected);
        List<String> renderedLabels = new ArrayList<>();
        Matcher renderedMatcher = VISIBLE_CITATION.matcher(rendered.answer());
        while (renderedMatcher.find()) {
            renderedLabels.add(visibleLabel(renderedMatcher));
        }
        if (!renderedLabels.equals(labels)) return null;
        return new AgentFinalAnswer(answer.answerType(), candidate, declared);
    }

    private static String visibleLabel(Matcher matcher) {
        return matcher.group(2) == null ? matcher.group(1) : matcher.group(1) + "-" + matcher.group(2);
    }

    private AgentFinalAnswer repairCitationBinding(AgentFinalAnswer answer, Collection<String> allowedIds) {
        String text = answer.answer() == null ? "" : answer.answer();
        Matcher matcher = AgentCitationIndexPlan.SEGMENT_MARKER.matcher(text);
        StringBuilder normalized = new StringBuilder();
        Set<String> kept = new LinkedHashSet<>();
        while (matcher.find()) {
            String segmentId = matcher.group(1).trim();
            if (!StringUtils.hasText(segmentId) || !allowedIds.contains(segmentId)) {
                matcher.appendReplacement(normalized, "");
                continue;
            }
            kept.add(segmentId);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
        }
        matcher.appendTail(normalized);
        if (kept.isEmpty()) return null;
        String repaired = normalized.toString();
        if (!citationPolicy.withinLimits(repaired)) repaired = citationPolicy.compactMarkers(repaired);
        List<String> markerIds = AgentCitationRenderer.extractSegmentIds(repaired);
        if (markerIds.isEmpty()) return null;
        return new AgentFinalAnswer(answer.answerType(), repaired, markerIds);
    }

    private AgentFinalAnswer repairCitationDensity(AgentFinalAnswer answer) {
        String compacted = citationPolicy.compactMarkers(answer.answer());
        if (!StringUtils.hasText(compacted) || !citationPolicy.withinLimits(compacted)) return null;
        List<String> markerIds = AgentCitationRenderer.extractSegmentIds(compacted);
        if (markerIds.isEmpty()) return null;
        return new AgentFinalAnswer(answer.answerType(), compacted, markerIds);
    }

    private AgentAnswerValidationOutcome verified(VerifiedAgentAnswer answer) {
        return new AgentAnswerValidationOutcome.Verified(answer);
    }

    private AgentAnswerValidationOutcome rejected(String code, String message, String fallbackReason) {
        return new AgentAnswerValidationOutcome.Rejected(code, message, fallbackReason);
    }

    private String noEvidenceAnswer(AgentState state) {
        AnswerMode mode = AnswerMode.from(state.runRequest().request().getAnswerMode());
        return switch (mode) {
            case STRICT -> "当前证据不足以回答该问题。请补充相关资料、缩小问题范围或指定文档后重试。";
            case SUMMARY -> "当前证据不足以形成可靠摘要。请补充相关资料或明确需要总结的文档范围。";
            case EXPLORE -> "当前证据不足以回答核心问题。请补充相关资料、缩小问题范围或明确希望探索的方向。";
        };
    }
}
