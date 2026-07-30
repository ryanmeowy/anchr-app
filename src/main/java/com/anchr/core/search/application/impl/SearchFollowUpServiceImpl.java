package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.SearchFollowUpService;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.domain.model.SegmentType;
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
 * LLM-powered follow-up question generation for search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchFollowUpServiceImpl implements SearchFollowUpService {

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[([^\\[\\]]*)]");
    private static final int MAX_CONTEXT_RESULTS = 3;
    private static final int MAX_SNIPPET_CHARS = 100;

    private final SearchGenerationPort generationPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public List<String> generate(String query, List<RetrievalHit> results) {
        meterRegistry.counter("search.followup.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (!StringUtils.hasText(query) || results == null || results.isEmpty()) {
                return List.of();
            }
            List<FollowUpContext> contexts = collectContexts(results);
            if (contexts.isEmpty()) {
                return List.of();
            }
            String prompt = buildPrompt(query.trim(), contexts);
            String raw = generationPort.generateText(prompt);
            List<String> questions = parseQuestions(raw);
            return questions.size() > 3 ? questions.subList(0, 3) : questions;
        } catch (Exception e) {
            log.warn("Follow-up question generation failed, queryLength={}, resultCount={}, "
                            + "errorType={}",
                    query == null ? 0 : query.length(),
                    results == null ? 0 : results.size(),
                    e.getClass().getSimpleName());
            meterRegistry.counter("search.followup.fallback.count").increment();
            return List.of();
        } finally {
            sample.stop(Timer.builder("search.followup.latency")
                    .description("Search follow-up question generation latency.")
                    .register(meterRegistry));
        }
    }

    private List<FollowUpContext> collectContexts(
            List<RetrievalHit> results
    ) {
        List<FollowUpContext> contexts = new ArrayList<>();
        for (RetrievalHit result : results) {
            if (result == null) {
                continue;
            }
            if (result.topChunks() == null
                    || result.topChunks().isEmpty()) {
                addContext(
                        contexts,
                        result.segmentType(),
                        result.snippet(),
                        result.score());
            } else {
                for (RetrievalTopChunk chunk : result.topChunks()) {
                    if (chunk != null) {
                        addContext(
                                contexts,
                                chunk.segmentType(),
                                chunk.snippet(),
                                chunk.score());
                    }
                    if (contexts.size() >= MAX_CONTEXT_RESULTS) {
                        break;
                    }
                }
            }
            if (contexts.size() >= MAX_CONTEXT_RESULTS) {
                break;
            }
        }
        return List.copyOf(contexts);
    }

    private void addContext(
            List<FollowUpContext> contexts,
            String segmentType,
            String snippet,
            Double score
    ) {
        if (contexts.size() >= MAX_CONTEXT_RESULTS
                || SegmentType.isImageVisual(segmentType)
                || !StringUtils.hasText(snippet)) {
            return;
        }
        contexts.add(new FollowUpContext(snippet, score));
    }

    private String buildPrompt(
            String query,
            List<FollowUpContext> contexts
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识库搜索助手。基于用户的搜索问题，生成 3 个推荐追问，帮助用户深入探索。\n");
        sb.append("用户问题：").append(query).append("\n\n");
        sb.append("检索结果摘要：\n");
        int count = 0;
        for (FollowUpContext context : contexts) {
            if (count >= MAX_CONTEXT_RESULTS) {
                break;
            }
            String snippet = context.snippet();
            if (snippet.length() > MAX_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_SNIPPET_CHARS);
            }
            Double score = context.score();
            sb.append("- [").append(count + 1).append("]");
            if (score != null) {
                sb.append(" (score: ").append(String.format(Locale.ROOT, "%.2f", score)).append(")");
            }
            sb.append(": ").append(snippet).append("\n");
            count++;
        }
        sb.append("\n要求：\n");
        sb.append("1) 追问应基于检索结果中实际出现的内容\n");
        sb.append("2) 追问应引导用户发现更深层的关联或细节\n");
        sb.append("3) 每个追问 ≤30 字，简洁自然\n");
        sb.append("4) 只输出 JSON 数组：[\"追问1\", \"追问2\", \"追问3\"]");
        return sb.toString();
    }

    private record FollowUpContext(String snippet, Double score) {
    }

    private List<String> parseQuestions(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String trimmed = raw.trim();
        // Try parse as JSON array
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isArray()) {
                List<String> questions = new ArrayList<>();
                for (JsonNode node : root) {
                    String text = node.asText(null);
                    if (StringUtils.hasText(text)) {
                        questions.add(text.trim());
                    }
                }
                return questions;
            }
        } catch (Exception ignored) {
            // fall through to fallback
        }
        // Fallback: extract [...] content and parse quoted strings
        Matcher m = JSON_ARRAY_PATTERN.matcher(trimmed);
        if (m.find()) {
            return parseQuotedStrings(m.group(1));
        }
        return List.of();
    }

    private List<String> parseQuotedStrings(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(content);
        while (m.find()) {
            String text = m.group(1).trim();
            if (StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return result;
    }
}
