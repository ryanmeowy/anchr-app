package com.anchr.core.common.constant;

import com.google.common.collect.Lists;

import java.util.List;

public class SearchConstant {

    public static final int MAX_HOT_WORDS = 10;
    public static final int HOT_WORDS_TREND_DAYS = 7;
    public static final int HOT_WORDS_BUCKET_TTL_DAYS = 8;
    public static final int HOT_WORDS_DAILY_FETCH_LIMIT = 200;
    public static final double HOT_WORDS_DECAY_BASE = 0.7d;

    public static final List<String> FALLBACK_WORDS = Lists.newArrayList("森林", "大海", "猫", "赛博朋克", "狗");

    public static final List<String> MOCK_BLOCKED_WORDS = Lists.newArrayList("色情", "暴力", "血腥");

    public static final Integer MAX_INPUT_LENGTH = 50;

    public static final Integer DEFAULT_RESULT_LIMIT = 20;


}
