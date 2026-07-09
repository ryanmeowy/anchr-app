package com.anchr.core.search.application.impl;

import com.anchr.core.common.constant.CacheConstant;
import com.anchr.core.search.application.SearchQueryRewriteService;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default search query keyword rewrite service.
 * <p>
 * Rewrites a standalone search query into a list of concise, semantically accurate
 * keywords to improve text-based retrieval recall. Results are cached in Redis
 * to avoid redundant LLM calls for the same query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchQueryRewriteServiceImpl implements SearchQueryRewriteService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");
    private static final Duration CACHE_TTL = Duration.ofHours(3);

    private final SearchGenerationPort generationPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public SearchRewriteResult rewrite(String query) {
        meterRegistry.counter("search.query.rewrite.count").increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        SearchRewriteResult fallback = buildFallback(query);
        try {
            if (!StringUtils.hasText(query)) {
                meterRegistry.counter("search.query.rewrite.fallback.count").increment();
                return fallback;
            }
            String trimmed = query.trim();
            String cacheKey = buildCacheKey(trimmed);

            // Check cache
            SearchRewriteResult cached = getCached(cacheKey);
            if (cached != null) {
                meterRegistry.counter("search.query.rewrite.cache.hit").increment();
                return cached;
            }

            String prompt = buildPrompt(trimmed);
            String raw = generationPort.generateText(prompt);
            SearchRewriteResult parsed = parseResult(trimmed, raw);
            if (parsed.getKeywords().isEmpty()) {
                meterRegistry.counter("search.query.rewrite.fallback.count").increment();
                return fallback;
            }
            parsed.setFallbackUsed(false);

            // Cache successful result
            setCache(cacheKey, parsed);

            return parsed;
        } catch (Exception e) {
            log.warn("Search query rewrite failed, query={}, message={}", query, e.getMessage());
            meterRegistry.counter("search.query.rewrite.fallback.count").increment();
            return fallback;
        } finally {
            sample.stop(Timer.builder("search.query.rewrite.latency")
                    .description("Search query rewrite latency.")
                    .register(meterRegistry));
        }
    }

    private String buildCacheKey(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        String hash = md5(normalized);
        return CacheConstant.SEARCH_REWRITE_CACHE_PREFIX + ":" + hash;
    }

    private SearchRewriteResult getCached(String cacheKey) {
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, SearchRewriteResult.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached rewrite result, key={}", cacheKey, e);
            return null;
        }
    }

    private void setCache(String cacheKey, SearchRewriteResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize rewrite result for cache, key={}", cacheKey, e);
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private SearchRewriteResult parseResult(String originalQuery, String rawText) {
        SearchRewriteResult result = buildFallback(originalQuery);
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
            List<String> keywords = readStringArray(root.path("keywords"));
            if (keywords.isEmpty()) {
                result.setFallbackUsed(true);
                return result;
            }
            result.setKeywords(keywords);

            String intent = root.path("intent").asText(null);
            if (StringUtils.hasText(intent)) {
                result.setIntent(intent.trim());
            }
            String category = root.path("category").asText(null);
            if (StringUtils.hasText(category)) {
                result.setIntentCategory(category.trim().toUpperCase(Locale.ROOT));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse search rewrite json, rawText={}", rawText);
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
            String value = item.asText(null);
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private String buildPrompt(String query) {
        return "你是检索关键字提取器。" +
                "目标：将用户搜索 query 分解为多个精简、准确的检索关键字，用于提升知识库文本检索的召回率。" +
                "同时判断用户的查询意图和类别。" +
                "必须只输出 JSON，不要输出解释性文字。" +
                "JSON schema: {\"keywords\":[\"string\"], \"intent\":\"string\", \"category\":\"string\"}。" +
                "约束：" +
                "1) 关键字应覆盖 query 中的核心概念、同义词和相关术语。" +
                "2) 每个关键字尽量简短（1-5 个词），适合全文检索。" +
                "3) 输出 3-5 个关键字，不要过多。" +
                "4) 保留原始 query 的完整语义。" +
                "5) 若 query 已经很精简，keywords 可以只包含原 query。" +
                "6) intent: 用中文简短描述查询意图（≤10字），如\"交互优化建议\"、\"技术原理解释\"、\"配置方法查询\"。" +
                "7) category: 从以下选择一个：HOW-TO | FACTUAL | DEFINITION | COMPARISON | TROUBLESHOOTING | OTHER。" +
                "搜索 query：" + query;
    }

    private SearchRewriteResult buildFallback(String query) {
        SearchRewriteResult fallback = new SearchRewriteResult();
        fallback.setOriginalQuery(query);
        fallback.setKeywords(List.of());
        fallback.setFallbackUsed(true);
        return fallback;
    }
}
