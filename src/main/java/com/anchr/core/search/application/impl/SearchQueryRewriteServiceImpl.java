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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default search query and keyword rewrite service.
 * <p>
 * Rewrites a standalone search query into a professional retrieval query and a
 * list of distinct concepts to improve retrieval quality. Results are cached in
 * Redis to avoid redundant LLM calls for the same query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchQueryRewriteServiceImpl implements SearchQueryRewriteService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");
    private static final Duration CACHE_TTL = Duration.ofHours(3);
    private static final String CACHE_VERSION = "v2";

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
            if (isSuccessfulRewrite(cached)) {
                meterRegistry.counter("search.query.rewrite.cache.hit").increment();
                return cached;
            }

            String prompt = buildPrompt(trimmed);
            String raw = generationPort.generateText(prompt);
            SearchRewriteResult parsed = parseResult(trimmed, raw);
            if (!isCompleteRewrite(parsed)) {
                meterRegistry.counter("search.query.rewrite.fallback.count").increment();
                return fallback;
            }
            parsed.setFallbackUsed(false);

            // Cache successful result
            setCache(cacheKey, parsed);

            return parsed;
        } catch (Exception e) {
            log.warn("Search query rewrite failed, queryLength={}, errorType={}",
                    query == null ? 0 : query.length(), e.getClass().getSimpleName());
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
        return CacheConstant.SEARCH_REWRITE_CACHE_PREFIX + ":" + CACHE_VERSION + ":" + hash;
    }

    private SearchRewriteResult getCached(String cacheKey) {
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, SearchRewriteResult.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached rewrite result, errorType={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private void setCache(String cacheKey, SearchRewriteResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize rewrite result for cache, errorType={}",
                    e.getClass().getSimpleName());
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
            String rewrittenQuery = root.path("rewrittenQuery").asText(null);
            if (!StringUtils.hasText(rewrittenQuery)) {
                result.setFallbackUsed(true);
                return result;
            }
            List<String> keywords = readStringArray(root.path("keywords"));
            if (keywords.isEmpty()) {
                result.setFallbackUsed(true);
                return result;
            }
            result.setRewrittenQuery(rewrittenQuery.trim());
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
            log.warn("Failed to parse search rewrite json, rawTextLength={}, errorType={}",
                    rawText.length(), e.getClass().getSimpleName());
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
        Set<String> normalizedValues = new HashSet<>();
        for (JsonNode item : node) {
            if (item == null) {
                continue;
            }
            String value = item.asText(null);
            if (StringUtils.hasText(value)) {
                String trimmed = value.trim();
                if (normalizedValues.add(trimmed.toLowerCase(Locale.ROOT))) {
                    values.add(trimmed);
                    if (values.size() >= 5) {
                        break;
                    }
                }
            }
        }
        return values;
    }

    private String buildPrompt(String query) throws JsonProcessingException {
        return """
                你是知识库检索查询改写器。请将用户的口语化查询改写成专业、精准、可独立理解且适合检索的查询，并提取其中彼此不同的核心概念。
                必须只输出 JSON，不要输出 Markdown 或解释性文字。
                JSON schema: {"rewrittenQuery":"string","keywords":["string"],"intent":"string","category":"string"}

                约束：
                1) rewrittenQuery 必须忠实保留原查询的意图、实体、专有名词、数字、时间、否定关系和限制条件；去除“请问、呢、吗、我想知道”等口语和冗余表达；使用专业、明确的术语，但不得添加原查询没有的事实或条件。
                2) keywords 只能提取原查询中实际包含的不同概念、实体或关键条件，不得生成同义词、近义词、同一概念的不同表述或相关词扩展。
                3) 每个 keyword 必须代表不同的信息维度；不要为了凑数量而拆分或重复概念。
                4) 每个 keyword 应简短、明确，通常为 1-5 个词；输出 1-5 个。原查询只有一个核心概念时只输出一个。
                5) keywords 不包含“的、了、呢、吗、和、请问”等无检索意义的口语或停用词。
                6) intent 用中文在 10 字以内描述查询意图，例如“技术原理解释”“配置方法查询”“故障原因排查”。
                7) category 必须从 HOW-TO | FACTUAL | DEFINITION | COMPARISON | TROUBLESHOOTING | OTHER 中选择一个。

                用户原始查询：
                """ + objectMapper.writeValueAsString(query);
    }

    private boolean isSuccessfulRewrite(SearchRewriteResult result) {
        return result != null
                && !result.isFallbackUsed()
                && isCompleteRewrite(result);
    }

    private boolean isCompleteRewrite(SearchRewriteResult result) {
        return result != null
                && StringUtils.hasText(result.getRewrittenQuery())
                && result.getKeywords() != null
                && !result.getKeywords().isEmpty();
    }

    private SearchRewriteResult buildFallback(String query) {
        SearchRewriteResult fallback = new SearchRewriteResult();
        fallback.setOriginalQuery(query);
        fallback.setRewrittenQuery(query);
        fallback.setKeywords(List.of());
        fallback.setFallbackUsed(true);
        return fallback;
    }
}
