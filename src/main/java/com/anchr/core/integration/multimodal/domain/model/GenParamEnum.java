package com.anchr.core.integration.multimodal.domain.model;

import lombok.Getter;

import java.util.List;

@Getter
public enum GenParamEnum {
        TEMPERATURE("temperature", "随机度 (0~2)"),
        MAX_TOKENS("max_tokens", "最大输出 token"),
        TOP_P("top_p", "核采样 (0~1)"),
        FREQUENCY_PENALTY("frequency_penalty", "重复惩罚 (-2~2)"),
        PRESENCE_PENALTY("presence_penalty", "话题惩罚 (-2~2)"),
        STOP("stop", "停止符"),
        STREAM("stream", "流式输出");

        private final String key;
        private final String label;

        GenParamEnum(String key, String label) { this.key = key; this.label = label; }

        public static List<GenParamEnum> all() { return List.of(values()); }
    }