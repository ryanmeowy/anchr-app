package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.SearchCitationReasonService;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-powered citation reason generation for search results.
 * <p>
 * Given a JSON payload containing score, hit sources, and match summary,
 * generates a concise human-readable explanation of why a search result
 * matched the query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchCitationReasonServiceImpl implements SearchCitationReasonService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");

    private final SearchGenerationPort generationPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public String generate(String query) {
        meterRegistry.counter("search.citation.reason.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (!StringUtils.hasText(query)) {
                meterRegistry.counter("search.citation.reason.fallback.count").increment();
                return null;
            }

            WhyInfo why = parseWhy(query.trim());
            if (why == null) {
                meterRegistry.counter("search.citation.reason.fallback.count").increment();
                return null;
            }

            String prompt = buildPrompt(why);
            String raw = generationPort.generateText(prompt);
            String reason = extractReason(raw);

            if (!StringUtils.hasText(reason)) {
                meterRegistry.counter("search.citation.reason.fallback.count").increment();
                return why.matchSummary;
            }

            return reason;
        } catch (Exception e) {
            log.warn("Citation reason generation failed, query={}", query, e);
            meterRegistry.counter("search.citation.reason.fallback.count").increment();
            return fallbackFromRaw(query);
        } finally {
            sample.stop(Timer.builder("search.citation.reason.latency")
                    .description("Search citation reason generation latency.")
                    .register(meterRegistry));
        }
    }

    private WhyInfo parseWhy(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode whyNode = root.path("why");
            if (whyNode.isMissingNode()) {
                return null;
            }
            WhyInfo info = new WhyInfo();

            JsonNode scoreNode = whyNode.path("score");
            if (!scoreNode.isMissingNode()) {
                info.score = scoreNode.asDouble();
            }

            JsonNode sourcesNode = whyNode.path("hitSources");
            if (sourcesNode.isArray()) {
                List<String> sources = new ArrayList<>();
                for (JsonNode s : sourcesNode) {
                    String text = s.asText(null);
                    if (StringUtils.hasText(text)) {
                        sources.add(text.trim());
                    }
                }
                info.hitSources = sources;
            }

            JsonNode summaryNode = whyNode.path("matchSummary");
            if (!summaryNode.isMissingNode()) {
                info.matchSummary = summaryNode.asText(null);
            }

            return info;
        } catch (Exception e) {
            log.warn("Failed to parse citation why json, input={}", json, e);
            return null;
        }
    }

    private String buildPrompt(WhyInfo why) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识库检索结果解释器。");
        sb.append("根据检索命中信息，生成一句简短、自然的中文解释，说明为什么该结果与用户查询相关。\n\n");
        sb.append("命中信息：\n");
        if (why.score != null) {
            sb.append("- 相关度得分: ").append(String.format(Locale.ROOT, "%.2f", why.score)).append("\n");
        }
        if (why.hitSources != null && !why.hitSources.isEmpty()) {
            sb.append("- 命中来源: ").append(String.join("、", why.hitSources)).append("\n");
        }
        if (StringUtils.hasText(why.matchSummary)) {
            sb.append("- 匹配摘要: ").append(why.matchSummary).append("\n");
        }
        sb.append("\n要求：\n");
        sb.append("1) 只输出一句中文解释，≤30 字\n");
        sb.append("2) 用自然语言概括匹配原因，不要重复数字或技术字段名\n");
        sb.append("3) 语气友好、易懂\n");
        sb.append("4) 不要输出 JSON，直接输出解释文本");
        return sb.toString();
    }

    private String extractReason(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();

        // Strip markdown json block if present
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            try {
                JsonNode root = objectMapper.readTree(matcher.group(1));
                String reason = root.path("reason").asText(null);
                if (StringUtils.hasText(reason)) {
                    return reason.trim();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        // Strip surrounding quotes
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        // If it's still a JSON object, try extracting "reason" field
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                String reason = root.path("reason").asText(null);
                if (StringUtils.hasText(reason)) {
                    return reason.trim();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
    }

    private String fallbackFromRaw(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode whyNode = root.path("why");
            JsonNode summaryNode = whyNode.path("matchSummary");
            if (!summaryNode.isMissingNode()) {
                return summaryNode.asText(null);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static class WhyInfo {
        Double score;
        List<String> hitSources;
        String matchSummary;
    }
}
