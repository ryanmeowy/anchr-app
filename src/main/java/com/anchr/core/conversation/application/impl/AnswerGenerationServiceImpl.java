package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerModePolicy;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.GenerationOptions;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.anchr.core.conversation.application.constant.ConversationConstant.DEFAULT_TIMEOUT;

/**
 * Default grounded answer generation service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerGenerationServiceImpl implements AnswerGenerationService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");
    private static final Pattern CITATION_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern ANSWERED_STATUS_PATTERN =
            Pattern.compile("\"status\"\\s*:\\s*\"ANSWERED\"");
    private static final String NO_EVIDENCE_TEMPLATE = """
            未找到足够内容支持该问题。
            你可以重试：
            1. 补充明确的实体名、版本号或术语
            2. 增加限定词（文档名、章节、页码、场景）
            3. 将问题拆成更小的单点问题后再提问
            """;
    private static final String GENERATION_FAILED_TEMPLATE =
            "回答模型未能生成可靠结果，请稍后重试。";

    private final ConversationGenerationPort generationPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RuntimeConfigUnit runtimeConfigUnit;

    @Override
    public AnswerGenerationResult generate(String userQuery,
                                           String rewrittenQuery,
                                           AnswerMode answerMode,
                                           List<ConversationRetrievalCandidate> topCandidates,
                                           List<ConversationCitation> citations) {
        return generateInternal(userQuery, rewrittenQuery, answerMode,
                topCandidates, citations, ConversationProgressListener.NOOP);
    }

    @Override
    public AnswerGenerationResult generateStream(String userQuery,
                                                 String rewrittenQuery,
                                                 AnswerMode answerMode,
                                                 List<ConversationRetrievalCandidate> topCandidates,
                                                 List<ConversationCitation> citations,
                                                 ConversationProgressListener progress) {
        return generateInternal(userQuery, rewrittenQuery, answerMode, topCandidates, citations,
                progress == null ? ConversationProgressListener.NOOP : progress);
    }

    private AnswerGenerationResult generateInternal(String userQuery,
                                                    String rewrittenQuery,
                                                    AnswerMode answerMode,
                                                    List<ConversationRetrievalCandidate> topCandidates,
                                                    List<ConversationCitation> citations,
                                                    ConversationProgressListener progress) {
        meterRegistry.counter("answer.generate.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        AnswerMode resolvedMode = answerMode == null ? AnswerMode.STRICT : answerMode;
        AnswerModePolicy policy = resolvedMode.policy();
        List<GroundingSegment> groundingSegments = pickGroundingSegments(topCandidates, citations, policy);
        boolean effectiveLegacyFallback = runtimeConfigUnit.getBoolean(
                RuntimeConfigType.CONVERSATION,
                ConversationRuntimeConfigKey.LEGACY_EVIDENCE_FALLBACK_ENABLED,
                false);
        try {
            String noEvidenceReason = resolveNoEvidenceReason(groundingSegments, topCandidates, policy);
            if (StringUtils.hasText(noEvidenceReason)) {
                meterRegistry.counter("answer.generate.fallback.count").increment();
                meterRegistry.counter("no_evidence.answer.rate").increment();
                return buildNoEvidenceFallback(noEvidenceReason);
            }

            String prompt = buildPrompt(userQuery, rewrittenQuery, groundingSegments, resolvedMode, policy);
            StreamingJsonAnswerDecoder decoder = new StreamingJsonAnswerDecoder(progress);
            GenerationOptions options = new GenerationOptions(null, null, DEFAULT_TIMEOUT);
            String rawText = progress.supportsAnswerStreaming()
                    ? generationPort.generateStream(
                            List.of(new ConversationModelMessage("user", prompt)),
                            options,
                            decoder::accept).content()
                    : generationPort.generate(
                            List.of(new ConversationModelMessage("user", prompt)), options);
            ModelAnswer modelAnswer = parseModelAnswer(rawText);
            if (modelAnswer == null) {
                return finalizeStream(buildGenerationFailureOrLegacyFallback(
                                groundingSegments, "invalid_model_response",
                                effectiveLegacyFallback),
                        decoder, progress);
            }
            if (modelAnswer.status() == ModelAnswerStatus.NO_EVIDENCE) {
                meterRegistry.counter("answer.generate.fallback.count").increment();
                meterRegistry.counter("no_evidence.answer.rate").increment();
                return finalizeStream(buildNoEvidenceFallback("model_declared_no_evidence"),
                        decoder, progress);
            }
            String answerText = modelAnswer.answer();
            if (!StringUtils.hasText(answerText)) {
                return finalizeStream(buildGenerationFailureOrLegacyFallback(
                                groundingSegments, "empty_model_answer",
                                effectiveLegacyFallback),
                        decoder, progress);
            }
            if (hasInvalidCitationReference(answerText, groundingSegments.size())) {
                return finalizeStream(buildGenerationFailureOrLegacyFallback(
                                groundingSegments, "invalid_answer_citation",
                                effectiveLegacyFallback),
                        decoder, progress);
            }
            NormalizedAnswerCitations normalized = normalizeAnswerCitations(answerText, groundingSegments);
            if (normalized == null) {
                return finalizeStream(buildGenerationFailureOrLegacyFallback(
                                groundingSegments, "missing_answer_citation",
                                effectiveLegacyFallback),
                        decoder, progress);
            }
            AnswerGenerationResult result = new AnswerGenerationResult();
            result.setAnswerText(normalized.answerText());
            result.setFallbackUsed(false);
            result.setFallbackReason(null);
            result.setAnswerInputSegmentIds(normalized.citedSegmentIds());
            return finalizeStream(result, decoder, progress);
        } catch (Exception e) {
            log.warn("Answer generation failed: {}", e.getMessage());
            AnswerGenerationResult failure = buildGenerationFailureOrLegacyFallback(
                    groundingSegments, "model_unavailable", effectiveLegacyFallback);
            if (progress.supportsAnswerStreaming()) progress.onAnswerReset(failure.getAnswerText());
            return failure;
        } finally {
            sample.stop(Timer.builder("answer.generate.latency")
                    .description("Conversation answer generation latency.")
                    .register(meterRegistry));
        }
    }

    private AnswerGenerationResult finalizeStream(AnswerGenerationResult result,
                                                  StreamingJsonAnswerDecoder decoder,
                                                  ConversationProgressListener progress) {
        if (progress.supportsAnswerStreaming() && decoder.emitted()
                && !decoder.emittedText().equals(result.getAnswerText())) {
            progress.onAnswerReset(result.getAnswerText());
        }
        return result;
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
                    segments.size() + 1,
                    citation.getFileName(),
                    citation.getPageNo(),
                    citation.getHitType(),
                    citation.getSegmentId(),
                    citation.getAssetId(),
                    evidence
            ));
        }
        return segments;
    }

    private String resolveEvidence(ConversationRetrievalCandidate candidate, ConversationCitation citation) {
        if (StringUtils.hasText(candidate.getContent())) {
            return candidate.getContent().trim();
        }
        if (StringUtils.hasText(candidate.getSnippet())) {
            return candidate.getSnippet().trim();
        }
        if (StringUtils.hasText(citation.getSnippet())) {
            return citation.getSnippet().trim();
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
        builder.append("必须只输出 JSON，不要输出解释性文字。");
        builder.append("JSON schema：{\"status\":\"ANSWERED|NO_EVIDENCE\",\"answer\":\"string\"}。");
        builder.append("回答模式：").append(answerMode.name()).append("。");
        builder.append(policy.styleInstruction());
        builder.append("当 status=ANSWERED 时，answer 必须遵守以下引用格式：");
        builder.append("引用编号必须紧跟在它所支持的总结、事实或结论之后，格式示例：“参数记忆保存模型内知识[1]，非参数记忆通过外部知识库提供事实依据[2]。”；");
        builder.append("其中前一句必须确实由证据[1]支持，后一句必须确实由证据[2]支持；");
        builder.append("一个陈述同时由多条证据支持时使用“结论[1][2]”，编号之间不加逗号、空格或其他文字；");
        builder.append("禁止把引用编号放在段首、要点符号之后或与对应内容分离；");
        builder.append("每个编号最多出现一次；同一证据支持的多个信息必须合并后再标注，且只能引用实际使用的给定证据；");
        builder.append("禁止输出“参考来源”“引用来源”“References”等独立标题、段落或结尾汇总。");
        if (policy.allowSpeculation()) {
            builder.append("如果提供可能方向或建议，必须单独成段，推测必须明确标注。");
        }
        builder.append("如果证据足以回答，status 必须为 ANSWERED。");
        builder.append("如果证据不足，status 必须为 NO_EVIDENCE，answer 只能使用“未找到足够内容支持该问题”，且不得输出任何引用编号。");
        builder.append("用户问题：").append(userQuery).append("。");
        builder.append("检索改写：").append(rewrittenQuery).append("。");
        builder.append("证据列表：");
        for (GroundingSegment segment : segments) {
            builder.append("[")
                    .append(segment.index())
                    .append("] asset=")
                    .append(StringUtils.hasText(segment.assetId()) ? segment.assetId() : "NA")
                    .append(",file=")
                    .append(StringUtils.hasText(segment.fileName()) ? segment.fileName() : "NA")
                    .append(",page=")
                    .append(segment.pageNo() == null ? "NA" : segment.pageNo())
                    .append(",type=")
                    .append(StringUtils.hasText(segment.hitType()) ? segment.hitType() : "NA")
                    .append(",content=")
                    .append(segment.evidence())
                    .append(";");
        }
        return builder.toString();
    }

    private ModelAnswer parseModelAnswer(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String json = extractJson(rawText.trim());
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String statusText = root.path("status").asText(null);
            ModelAnswerStatus status = ModelAnswerStatus.from(statusText);
            if (status == null) {
                return null;
            }
            String answer = root.path("answer").asText(null);
            return new ModelAnswer(status, StringUtils.hasText(answer) ? answer.trim() : null);
        } catch (Exception e) {
            return null;
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

    private NormalizedAnswerCitations normalizeAnswerCitations(String answerText,
                                                                List<GroundingSegment> groundingSegments) {
        Matcher matcher = CITATION_REFERENCE_PATTERN.matcher(answerText);
        Map<Integer, Integer> normalizedIndexes = new LinkedHashMap<>();
        Map<String, Integer> documentIndexes = new LinkedHashMap<>();
        while (matcher.find()) {
            int originalIndex = parseCitationNumber(matcher.group(1));
            GroundingSegment segment = groundingSegments.get(originalIndex - 1);
            String documentKey = StringUtils.hasText(segment.assetId())
                    ? segment.assetId().trim() : "__segment__" + segment.segmentId();
            int documentIndex = documentIndexes.computeIfAbsent(documentKey, ignored -> documentIndexes.size() + 1);
            normalizedIndexes.putIfAbsent(originalIndex, documentIndex);
        }
        if (normalizedIndexes.isEmpty()) {
            return null;
        }

        matcher.reset();
        StringBuilder normalizedAnswer = new StringBuilder();
        Set<Integer> emittedDocumentIndexes = new LinkedHashSet<>();
        while (matcher.find()) {
            int originalIndex = parseCitationNumber(matcher.group(1));
            Integer documentIndex = normalizedIndexes.get(originalIndex);
            String replacement = emittedDocumentIndexes.add(documentIndex)
                    ? "[" + documentIndex + "]"
                    : "";
            matcher.appendReplacement(normalizedAnswer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(normalizedAnswer);

        List<String> citedSegmentIds = normalizedIndexes.keySet().stream()
                .map(index -> groundingSegments.get(index - 1).segmentId())
                .filter(StringUtils::hasText)
                .toList();
        return new NormalizedAnswerCitations(normalizedAnswer.toString().trim(), citedSegmentIds);
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

    private AnswerGenerationResult buildNoEvidenceFallback(String reason) {
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText(NO_EVIDENCE_TEMPLATE.trim());
        result.setFallbackUsed(true);
        result.setFallbackReason("no_evidence_" + reason);
        result.setAnswerInputSegmentIds(List.of());
        return result;
    }

    private AnswerGenerationResult buildGenerationFailureOrLegacyFallback(
            List<GroundingSegment> segments,
            String reason,
            boolean effectiveLegacyFallback) {
        meterRegistry.counter("answer.generate.failure.count", "reason", reason).increment();
        if (!effectiveLegacyFallback) {
            return buildGenerationFailure(reason);
        }
        meterRegistry.counter("answer.generate.fallback.count").increment();
        return buildModelFallback(segments, reason);
    }

    private AnswerGenerationResult buildGenerationFailure(String reason) {
        AnswerGenerationResult result = new AnswerGenerationResult();
        result.setAnswerText(GENERATION_FAILED_TEMPLATE);
        result.setFallbackUsed(false);
        result.setGenerationFailed(true);
        result.setFallbackReason(reason);
        result.setAnswerInputSegmentIds(List.of());
        return result;
    }

    private AnswerGenerationResult buildModelFallback(List<GroundingSegment> segments, String reason) {
        StringBuilder answer = new StringBuilder();
        answer.append("根据当前知识库，先给出可确认的信息：");
        Map<String, List<GroundingSegment>> segmentsByDocument = new LinkedHashMap<>();
        for (GroundingSegment segment : segments) {
            String documentKey = StringUtils.hasText(segment.assetId())
                    ? segment.assetId().trim() : "__segment__" + segment.segmentId();
            segmentsByDocument.computeIfAbsent(documentKey, ignored -> new ArrayList<>()).add(segment);
        }
        int documentIndex = 0;
        for (List<GroundingSegment> documentSegments : segmentsByDocument.values()) {
            documentIndex++;
            answer.append(System.lineSeparator())
                    .append("- ");
            for (int i = 0; i < documentSegments.size(); i++) {
                if (i > 0) {
                    answer.append("；");
                }
                answer.append(documentSegments.get(i).evidence());
            }
            answer.append("[")
                    .append(documentIndex)
                    .append("]");
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
                                    String assetId,
                                    String evidence) {
    }

    private record ModelAnswer(ModelAnswerStatus status, String answer) {
    }

    private record NormalizedAnswerCitations(String answerText, List<String> citedSegmentIds) {
    }

    private enum ModelAnswerStatus {
        ANSWERED,
        NO_EVIDENCE;

        private static ModelAnswerStatus from(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return valueOf(value.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /**
     * Incrementally extracts and decodes the JSON string stored in the answer
     * field. JSON protocol bytes and status metadata never reach the browser.
     */
    private static final class StreamingJsonAnswerDecoder {
        private final ConversationProgressListener progress;
        private final StringBuilder raw = new StringBuilder();
        private String emittedText = "";

        private StreamingJsonAnswerDecoder(ConversationProgressListener progress) {
            this.progress = progress;
        }

        private void accept(String delta) {
            if (delta == null || delta.isEmpty()) return;
            raw.append(delta);
            if (!ANSWERED_STATUS_PATTERN.matcher(raw).find()) return;
            String decoded = extractPartialStringField(raw.toString(), "answer");
            if (decoded == null || !decoded.startsWith(emittedText)) return;
            String next = decoded.substring(emittedText.length());
            if (!next.isEmpty()) {
                emittedText = decoded;
                progress.onAnswerDelta(next);
            }
        }

        private boolean emitted() {
            return !emittedText.isEmpty();
        }

        private String emittedText() {
            return emittedText;
        }

        private static String extractPartialStringField(String json, String field) {
            int key = json.indexOf("\"" + field + "\"");
            if (key < 0) return null;
            int colon = json.indexOf(':', key + field.length() + 2);
            if (colon < 0) return null;
            int quote = colon + 1;
            while (quote < json.length() && Character.isWhitespace(json.charAt(quote))) quote++;
            if (quote >= json.length() || json.charAt(quote) != '"') return null;

            StringBuilder decoded = new StringBuilder();
            for (int i = quote + 1; i < json.length(); i++) {
                char value = json.charAt(i);
                if (value == '"') return decoded.toString();
                if (value != '\\') {
                    decoded.append(value);
                    continue;
                }
                if (++i >= json.length()) return decoded.toString();
                char escaped = json.charAt(i);
                switch (escaped) {
                    case '"' -> decoded.append('"');
                    case '\\' -> decoded.append('\\');
                    case '/' -> decoded.append('/');
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (i + 4 >= json.length()) return decoded.toString();
                        String hex = json.substring(i + 1, i + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ignored) {
                            return decoded.toString();
                        }
                        i += 4;
                    }
                    default -> decoded.append(escaped);
                }
            }
            return decoded.toString();
        }
    }
}
