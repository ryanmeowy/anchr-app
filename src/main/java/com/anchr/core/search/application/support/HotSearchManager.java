
package com.anchr.core.search.application.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static com.anchr.core.common.constant.CacheConstant.HOT_WORDS_CACHE_PREFIX;
import static com.anchr.core.common.constant.CommonConstant.PROFILE_KEY_NAME;
import static com.anchr.core.common.constant.SearchConstant.FALLBACK_WORDS;
import static com.anchr.core.common.constant.SearchConstant.HOT_WORDS_BUCKET_TTL_DAYS;
import static com.anchr.core.common.constant.SearchConstant.HOT_WORDS_DAILY_FETCH_LIMIT;
import static com.anchr.core.common.constant.SearchConstant.HOT_WORDS_DECAY_BASE;
import static com.anchr.core.common.constant.SearchConstant.HOT_WORDS_TREND_DAYS;
import static com.anchr.core.common.constant.SearchConstant.MAX_HOT_WORDS;
import static com.anchr.core.common.constant.SearchConstant.MAX_INPUT_LENGTH;
import static com.anchr.core.common.constant.SearchConstant.MOCK_BLOCKED_WORDS;


/**
 * HotSearchManager is responsible for managing hot search keywords.
 * It provides functionality to record search terms and retrieve the top hot words.
 * The class uses Redis for storing and managing the hot search data.
 *
 * @author Ryan
 * @since 2025/12/22
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class HotSearchManager {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate stringRedisTemplate;
    
    /**
     * Asynchronously record search terms (Fire and Forget)
     */
    @Async("hotWordsTaskExecutor")
    public void incrementScore(String keyword) {
        if (keyword == null) {
            return;
        }

        String normalizedWord = keyword.trim().toLowerCase(Locale.ROOT);

        if (MOCK_BLOCKED_WORDS.contains(normalizedWord) || normalizedWord.length() > MAX_INPUT_LENGTH) {
            return;
        }

        try {
            String cacheKey = buildDailyKey(currentDate());
            stringRedisTemplate.opsForZSet().incrementScore(cacheKey, normalizedWord, 1.0);
            stringRedisTemplate.expire(cacheKey, HOT_WORDS_BUCKET_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Failed to count hot words: {}", e.getMessage());
        }
    }

    /**
     * Get Top N hot words
     */
    public List<String> getTopHotWords() {
        Map<String, Double> trendScores = aggregateTrendScores();
        if (trendScores.isEmpty()) {
            return FALLBACK_WORDS;
        }

        List<String> ranked = trendScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_HOT_WORDS)
                .map(Map.Entry::getKey)
                .toList();
        return mergeWithFallback(ranked);
    }

    protected LocalDate currentDate() {
        return LocalDate.now();
    }

    private Map<String, Double> aggregateTrendScores() {
        Map<String, Double> aggregate = new HashMap<>();
        LocalDate today = currentDate();
        for (int dayOffset = 0; dayOffset < HOT_WORDS_TREND_DAYS; dayOffset++) {
            LocalDate date = today.minusDays(dayOffset);
            String key = buildDailyKey(date);
            double weight = Math.pow(HOT_WORDS_DECAY_BASE, dayOffset);
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, HOT_WORDS_DAILY_FETCH_LIMIT - 1);
            if (tuples == null || tuples.isEmpty()) {
                continue;
            }
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                aggregate.merge(tuple.getValue(), tuple.getScore() * weight, Double::sum);
            }
        }
        return aggregate;
    }

    private List<String> mergeWithFallback(List<String> rankedWords) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(rankedWords);
        if (merged.size() < MAX_HOT_WORDS) {
            for (String fallback : FALLBACK_WORDS) {
                if (merged.size() >= MAX_HOT_WORDS) {
                    break;
                }
                merged.add(fallback);
            }
        }
        return new ArrayList<>(merged);
    }

    private String buildDailyKey(LocalDate date) {
        return String.format("%s:%s:d:%s", HOT_WORDS_CACHE_PREFIX, System.getenv(PROFILE_KEY_NAME), DAY_FORMATTER.format(date));
    }
}
