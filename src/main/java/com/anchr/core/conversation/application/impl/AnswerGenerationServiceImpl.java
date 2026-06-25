package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerModePolicy;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default grounded answer generation service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerGenerationServiceImpl implements AnswerGenerationService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");
    private static final Pattern CITATION_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final String NO_EVIDENCE_TEMPLATE = """
            未找到足够内容支持该问题。
            建议改写检索问题：%s
            你可以重试：
            1. 补充明确的实体名、版本号或术语
            2. 增加限定词（文档名、章节、页码、场景）
            3. 将问题拆成更小的单点问题后再提问
            """;

    private final ConversationRewritePort conversationRewritePort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public AnswerGenerationResult generate(String userQuery,
                                           String rewrittenQuery,
                                           AnswerMode answerMode,
                                           List<ConversationRetrievalCandidate> topCandidates,
                                           List<ConversationCitation> citations) {
        meterRegistry.counter("answer.generate.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        AnswerMode resolvedMode = answerMode == null ? AnswerMode.STRICT : answerMode;
        AnswerModePolicy policy = resolvedMode.policy();
        List<GroundingSegment> groundingSegments = pickGroundingSegments(topCandidates, citations, policy);
        try {
            String noEvidenceReason = resolveNoEvidenceReason(groundingSegments, topCandidates, policy);
            if (StringUtils.hasText(noEvidenceReason)) {
                meterRegistry.counter("answer.generate.fallback.count").increment();
                meterRegistry.counter("no_evidence.answer.rate").increment();
                return buildNoEvidenceFallback(userQuery, rewrittenQuery, noEvidenceReason);
            }

            String prompt = buildPrompt(userQuery, rewrittenQuery, groundingSegments, resolvedMode, policy);
            String rawText = conversationRewritePort.generateText(prompt);
            String answerText = parseAnswer(rawText);
            if (!StringUtils.hasText(answerText)) {
                meterRegistry.counter("answer.generate.fallback.count").increment();
                return buildModelFallback(groundingSegments, "empty_model_answer");
            }
            if (hasInvalidCitationReference(answerText, groundingSegments.size())) {
                meterRegistry.counter("answer.generate.fallback.count").increment();
                return buildModelFallback(groundingSegments, "invalid_answer_citation");
            }
            AnswerGenerationResult result = new AnswerGenerationResult();
            result.setAnswerText(answerText.trim());
            result.setFallbackUsed(false);
            result.setFallbackReason(null);
            result.setAnswerInputSegmentIds(collectSegmentIds(groundingSegments));
            return result;
        } catch (Exception e) {
            log.warn("Answer generation failed: {}", e.getMessage());
            meterRegistry.counter("answer.generate.fallback.count").increment();
            return buildModelFallback(groundingSegments, "model_unavailable");
        } finally {
            sample.stop(Timer.builder("answer.generate.latency")
                    .description("Conversation answer generation latency.")
                    .register(meterRegistry));
        }
    }

    private List<GroundingSegment> pickGroundingSegments(List<ConversationRetrievalCandidate> topCandidates,
                                                         List<ConversationCitation> citations,
                                                         AnswerModePolicy policy) {
        if (topCandidates == null || topCandidates.isEmpty() || citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<GroundingSegment> segments = new ArrayList<>();
        int limit = Math.min(Math.min(topCandidates.size(), citations.size()), policy.groundingLimit());
        for (int i = 0; i < limit; i++) {
            ConversationRetrievalCandidate candidate = topCandidates.get(i);
            ConversationCitation citation = citations.get(i);
            if (candidate == null || citation == null) {
                continue;
            }
            String evidence = resolveEvidence(candidate, citation);
            if (!StringUtils.hasText(evidence)) {
                continue;
            }
            segments.add(new GroundingSegment(
                    i + 1,
                    citation.getFileName(),
                    citation.getPageNo(),
                    citation.getHitType(),
                    citation.getSegmentId(),
                    evidence
            ));
        }
        return segments;
    }

    private String resolveEvidence(ConversationRetrievalCandidate candidate, ConversationCitation citation) {
        if (StringUtils.hasText(citation.getSnippet())) {
            return citation.getSnippet().trim();
        }
        if (StringUtils.hasText(candidate.getSnippet())) {
            return candidate.getSnippet().trim();
        }
        if (candidate.getTopChunks() != null && !candidate.getTopChunks().isEmpty()) {
            ConversationRetrievalCandidate.TopChunk first = candidate.getTopChunks().getFirst();
            if (first != null && StringUtils.hasText(first.getSnippet())) {
                return first.getSnippet().trim();
            }
        }
        return null;
    }

    private String buildPrompt(String userQuery,
                               String rewrittenQuery,
                               List<GroundingSegment> segments,
                               AnswerMode answerMode,
                               AnswerModePolicy policy) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是知识库问答助手。");
        builder.append("只能基于给定证据回答，不得编造。");
        builder.append("输出 JSON：{\"answer\":\"string\"}。");
        builder.append("回答模式：").append(answerMode.name()).append("。");
        builder.append(policy.styleInstruction());
        builder.append("引用格式：必须使用 [1] [2] 这种编号，且编号只能引用给定证据。");
        if (policy.allowSpeculation()) {
            builder.append("如果提供可能方向或建议，必须单独成段并明确标注为推测。");
        }
        builder.append("如果证据不足，请直接回答“未找到足够内容支持该问题”。");
        builder.append("用户问题：").append(userQuery).append("。");
        builder.append("检索改写：").append(rewrittenQuery).append("。");
        builder.append("证据列表：");
        for (GroundingSegment segment : segments) {
            builder.append("[")
                    .append(segment.index())
                    .append("] file=")
                    .append(segment.fileName())
                    .append(",page=")
                    .append(segment.pageNo() == null ? "NA" : segment.pageNo())
                    .append(",type=")
                    .append(segment.hitType())
                    .append(",snippet=")
                    .append(segment.evidence())
                    .append(";");
        }
        return builder.toString();
    }

    private String parseAnswer(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String json = extractJson(rawText.trim());
        if (!StringUtils.hasText(json)) {
            return rawText.trim();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String answer = root.path("answer").asText(null);
            return StringUtils.hasText(answer) ? answer.trim() : null;
        } catch (Exception e) {
            return rawText.trim();
        }
    }

    private String extractJson(String text) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        return null;
    }

    private boolean hasInvalidCitationReference(String answerText, int evidenceCount) {
        Matcher matcher = CITATION_REFERENCE_PATTERN.matcher(answerText);
        while (matcher.find()) {
            int citationNumber = parseCitationNumber(matcher.group(1));
            if (citationNumber < 1 || citationNumber > evidenceCount) {
                return true;
            }
        }
        return false;
    }

    private int parseCitationNumber(String citationText) {
        try {
            return Integer.parseInt(citationText);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String resolveNoEvidenceReason(List<GroundingSegment> groundingSegments,
                                           List<ConversationRetrievalCandidate> topCandidates,
                                           AnswerModePolicy policy) {
        if (groundingSegments == null || groundingSegments.isEmpty()) {
            return "no_grounding_segment";
        }
        int totalEvidenceChars = groundingSegments.stream()
                .map(segment -> segment.evidence() == null ? 0 : segment.evidence().length())
                .reduce(0, Integer::sum);
        if (totalEvidenceChars < policy.minEvidenceChars() && groundingSegments.size() < 2) {
            return "evidence_too_short";
        }
        double maxScore = resolveMaxScore(topCandidates);
        if (maxScore >= 0D && maxScore < policy.minTopScore() && groundingSegments.size() < 2) {
            return "low_retrieval_score";
        }
        return null;
    }

    private double resolveMaxScore(List<ConversationRetrievalCandidate> topCandidates) {
        if (topCandidates == null || topCandidates.isEmpty()) {
            return -1D;
        }
        double maxScore = -1D;
        for (ConversationRetrievalCandidate candidate : topCandidates) {
            if (candidate == null || candidate.getScore() == null) {
                continue;
            }
            maxScore = Math.max(maxScore, candidate.getScore());
        }
        return maxScore;
    }

    private AnswerGenerationResult buildNoEvidenceFallback(String userQuery, String rewrittenQuery, String reason) {
        String rewriteSuggestion = resolveRewriteSuggestion(userQuery, rewrittenQuery);
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText(NO_EVIDENCE_TEMPLATE.formatted(rewriteSuggestion).trim());
        result.setFallbackUsed(true);
        result.setFallbackReason("no_evidence_" + reason);
        result.setAnswerInputSegmentIds(List.of());
        return result;
    }

    private String resolveRewriteSuggestion(String userQuery, String rewrittenQuery) {
        if (StringUtils.hasText(rewrittenQuery)) {
            return rewrittenQuery.trim();
        }
        if (StringUtils.hasText(userQuery)) {
            return userQuery.trim();
        }
        return "请补充更明确的问题描述";
    }

    private AnswerGenerationResult buildModelFallback(List<GroundingSegment> segments, String reason) {
        StringBuilder answer = new StringBuilder();
        answer.append("根据当前知识库，先给出可确认的信息：");
        for (GroundingSegment segment : segments) {
            answer.append(System.lineSeparator())
                    .append("- [")
                    .append(segment.index())
                    .append("] ")
                    .append(segment.evidence());
        }
        answer.append(System.lineSeparator()).append("如需更精确答案，请继续追问。");
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText(answer.toString());
        result.setFallbackUsed(true);
        result.setFallbackReason(reason);
        result.setAnswerInputSegmentIds(collectSegmentIds(segments));
        return result;
    }

    private List<String> collectSegmentIds(List<GroundingSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        return segments.stream()
                .map(GroundingSegment::segmentId)
                .filter(StringUtils::hasText)
                .toList();
    }

    private record GroundingSegment(int index,
                                    String fileName,
                                    Integer pageNo,
                                    String hitType,
                                    String segmentId,
                                    String evidence) {
    }
}
