package com.anchr.core.settings.domain.model;

import lombok.Getter;

import java.util.List;

@Getter
public enum EmbedParamEnum {
        /** Output vector dimension. */
        DIMENSIONS("dimensions", "输出向量维度");

        private final String key;
        private final String label;

        EmbedParamEnum(String key, String label) { this.key = key; this.label = label; }

        public static List<EmbedParamEnum> all() { return List.of(values()); }
    }