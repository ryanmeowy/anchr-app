package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.RetrievalCitationReasonApi;
import com.anchr.core.search.application.api.model.RetrievalCitationReasonRequest;
import com.anchr.core.search.application.api.model.RetrievalCitationReasonRequest.CitationChunk;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.anchr.core.common.constant.CitationConstant.REASON_MAX_LENGTH;

/**
 * Batch LLM generation for final citation explanations. Model failure never changes citations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CitationReasonGenerationServiceImpl implements RetrievalCitationReasonApi {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");
    private final SearchGenerationPort generationPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Map<String, String> generate(RetrievalCitationReasonRequest request) {
        Map<String, String> fallbackReasons = fallbackReasons(request);
        List<CitationChunk> eligibleChunks = eligibleChunks(request);
        if (eligibleChunks.isEmpty()) {
            return fallbackReasons;
        }

        meterRegistry.counter("citation.reason.generate.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String raw = generationPort.generateText(buildPrompt(request));
            Map<String, String> generated = parseReasons(raw, eligibleChunks);
            Map<String, String> resolved = new LinkedHashMap<>(fallbackReasons);
            generated.forEach(resolved::put);
            int fallbackCount = Math.max(0, eligibleChunks.size() - generated.size());
            meterRegistry.summary("citation.reason.generated.item.count").record(generated.size());
            if (fallbackCount > 0) {
                meterRegistry.counter("citation.reason.fallback.count").increment(fallbackCount);
            }
            return resolved;
        } catch (Exception e) {
            log.warn("Citation reason batch generation failed: {}", e.getMessage());
            meterRegistry.counter("citation.reason.failure.count").increment();
            meterRegistry.counter("citation.reason.fallback.count").increment(eligibleChunks.size());
            return fallbackReasons;
        } finally {
            sample.stop(Timer.builder("citation.reason.generate.latency")
                    .description("Batch citation reason generation latency.")
                    .register(meterRegistry));
        }
    }

    private String buildPrompt(RetrievalCitationReasonRequest request) throws Exception {
        String payload = objectMapper.writeValueAsString(request);
        return """
                你是知识库回答的引用解释器。请根据用户问题、最终回答和每个引用 Chunk 的原始正文，解释该 Chunk 如何支持回答中对应引用编号附近的论点。
                检索得分、命中来源和匹配摘要仅用于辅助判断，禁止在理由中复述分数、字段名或技术命中信号。
                不得补充 Chunk 正文中不存在的事实。每个理由必须具体、自然、中文、不超过 50 个汉字。
                只输出 JSON，schema 为：{"items":[{"segmentId":"string","reason":"string"}]}。
                必须为每个具有非空 content 的输入 Chunk 返回一项，segmentId 必须原样返回。
                输入：
                """ + payload;
    }

    private Map<String, String> parseReasons(String raw, List<CitationChunk> eligibleChunks) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        String json = extractJson(raw.trim());
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        Set<String> allowedIds = eligibleChunks.stream()
                .map(CitationChunk::segmentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> reasons = new LinkedHashMap<>();
        try {
            JsonNode items = objectMapper.readTree(json).path("items");
            if (!items.isArray()) {
                return Map.of();
            }
            for (JsonNode item : items) {
                String segmentId = item.path("segmentId").asText(null);
                String reason = sanitizeReason(item.path("reason").asText(null));
                if (!StringUtils.hasText(segmentId)
                        || !allowedIds.contains(segmentId)
                        || !StringUtils.hasText(reason)) {
                    continue;
                }
                reasons.putIfAbsent(segmentId, reason);
            }
            return reasons;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractJson(String raw) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return raw.startsWith("{") && raw.endsWith("}") ? raw : null;
    }

    private String sanitizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= REASON_MAX_LENGTH
                ? trimmed : trimmed.substring(0, REASON_MAX_LENGTH);
    }

    private List<CitationChunk> eligibleChunks(RetrievalCitationReasonRequest request) {
        if (request == null || request.citations() == null) {
            return List.of();
        }
        return request.citations().stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(group -> group.chunks() == null ? java.util.stream.Stream.empty() : group.chunks().stream())
                .filter(java.util.Objects::nonNull)
                .filter(chunk -> StringUtils.hasText(chunk.segmentId()) && StringUtils.hasText(chunk.content()))
                .toList();
    }

    private Map<String, String> fallbackReasons(RetrievalCitationReasonRequest request) {
        if (request == null || request.citations() == null) {
            return Map.of();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        request.citations().stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(group -> group.chunks() == null ? java.util.stream.Stream.empty() : group.chunks().stream())
                .filter(java.util.Objects::nonNull)
                .filter(chunk -> StringUtils.hasText(chunk.segmentId()) && StringUtils.hasText(chunk.matchSummary()))
                .forEach(chunk -> reasons.putIfAbsent(
                        chunk.segmentId(), sanitizeReason(chunk.matchSummary())));
        return reasons;
    }
}
