package com.anchr.core.integration.multimodal.domain.model;

import lombok.Getter;

import java.util.List;

@Getter
public enum RerankParamEnum {
        TOP_N("top_n", "返回条数"),
        RETURN_DOCUMENTS("return_documents", "是否返回原文");

        private final String key;
        private final String label;

        RerankParamEnum(String key, String label) { this.key = key; this.label = label; }

        public static List<RerankParamEnum> all() { return List.of(values()); }
    }