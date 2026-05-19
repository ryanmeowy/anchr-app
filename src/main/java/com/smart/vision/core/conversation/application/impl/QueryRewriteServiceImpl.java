package com.smart.vision.core.conversation.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.conversation.application.QueryRewriteService;
import com.smart.vision.core.conversation.application.model.RewriteResult;
import com.smart.vision.core.conversation.domain.model.ConversationTurn;
import com.smart.vision.core.conversation.domain.port.ConversationRewritePort;
import com.smart.vision.core.conversation.domain.repository.ConversationRepository;
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
 * Default query rewrite service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final int CONTEXT_TURN_LIMIT = 5;
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");

    private final ConversationRepository conversationRepository;
    private final ConversationRewritePort conversationRewritePort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public RewriteResult rewrite(String sessionId, String latestQuery) {
        meterRegistry.counter("query.rewrite.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        RewriteResult fallback = buildFallback(latestQuery, "fallback_original_query");
        try {
            if (!StringUtils.hasText(latestQuery)) {
                meterRegistry.counter("query.rewrite.fallback.count").increment();
                return fallback;
            }
            List<ConversationTurn> recentTurns = conversationRepository.findRecentTurns(sessionId, CONTEXT_TURN_LIMIT);
            String prompt = buildPrompt(latestQuery.trim(), recentTurns);
            String raw = conversationRewritePort.generateText(prompt);
            RewriteResult parsed = parseRewriteResult(latestQuery.trim(), raw);
            if (!StringUtils.hasText(parsed.getRewrittenQuery())) {
                meterRegistry.counter("query.rewrite.fallback.count").increment();
                return fallback;
            }
            parsed.setFallbackUsed(false);
            return parsed;
        } catch (Exception e) {
            log.warn("Query rewrite failed, sessionId={}, message={}", sessionId, e.getMessage());
            meterRegistry.counter("query.rewrite.fallback.count").increment();
            return fallback;
        } finally {
            sample.stop(Timer.builder("query.rewrite.latency")
                    .description("Conversation query rewrite latency.")
                    .register(meterRegistry));
        }
    }

    private RewriteResult parseRewriteResult(String latestQuery, String rawText) {
        RewriteResult result = buildFallback(latestQuery, "rewrite_by_model");
        if (!StringUtils.hasText(rawText)) {
            result.setFallbackUsed(true);
            return result;
        }

        String json = extractJson(rawText);
        if (!StringUtils.hasText(json)) {
            result.setFallbackUsed(true);
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String rewrittenQuery = trimToNull(root.path("rewrittenQuery").asText(null));
            if (StringUtils.hasText(rewrittenQuery)) {
                result.setRewrittenQuery(rewrittenQuery);
            }
            String reason = trimToNull(root.path("rewriteReason").asText(null));
            if (StringUtils.hasText(reason)) {
                result.setRewriteReason(reason);
            }
            result.setTopicEntities(readStringArray(root.path("topicEntities")));
            result.setPreferredModalities(readStringArray(root.path("preferredModalities")));
            double confidence = root.path("confidence").asDouble(0.0D);
            result.setConfidence(Math.max(0.0D, Math.min(1.0D, confidence)));
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse rewrite json, rawText={}", rawText);
            result.setFallbackUsed(true);
            return result;
        }
    }

    private String extractJson(String rawText) {
        String trimmed = rawText.trim();
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null) {
                continue;
            }
            String value = trimToNull(item.asText(null));
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String buildPrompt(String latestQuery, List<ConversationTurn> recentTurns) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是检索 query 重写器。");
        builder.append("目标：结合最近对话上下文，把用户最新问题改写为适合知识库检索的单行查询。");
        builder.append("必须只输出 JSON，不要输出解释性文字。");
        builder.append("JSON schema: {");
        builder.append("\"rewrittenQuery\":\"string\",");
        builder.append("\"rewriteReason\":\"string\",");
        builder.append("\"topicEntities\":[\"string\"],");
        builder.append("\"preferredModalities\":[\"TEXT|IMAGE|MIXED\"],");
        builder.append("\"confidence\":0.0");
        builder.append("}。");
        builder.append("约束：");
        builder.append("1) 保留用户原意，避免扩写无关内容。");
        builder.append("2) 若上下文不足，rewrittenQuery 直接使用原问题。");
        builder.append("3) confidence 范围 0~1。");
        builder.append("最近对话（按时间倒序）：");
        for (ConversationTurn turn : recentTurns) {
            if (turn == null) {
                continue;
            }
            String query = trimToNull(turn.getQuery());
            String rewritten = trimToNull(turn.getRewrittenQuery());
            if (!StringUtils.hasText(query) && !StringUtils.hasText(rewritten)) {
                continue;
            }
            builder.append("[turnId=").append(turn.getTurnId()).append("]");
            if (StringUtils.hasText(query)) {
                builder.append(" userQuery=").append(query).append(";");
            }
            if (StringUtils.hasText(rewritten)) {
                builder.append(" rewritten=").append(rewritten).append(";");
            }
        }
        builder.append("最新问题：").append(latestQuery);
        return builder.toString();
    }

    private RewriteResult buildFallback(String latestQuery, String reason) {
        RewriteResult fallback = new RewriteResult();
        fallback.setOriginalQuery(latestQuery);
        fallback.setRewrittenQuery(latestQuery);
        fallback.setRewriteReason(reason);
        fallback.setTopicEntities(List.of());
        fallback.setPreferredModalities(List.of("MIXED"));
        fallback.setConfidence(0.0D);
        fallback.setFallbackUsed(true);
        return fallback;
    }

    private String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }
}
