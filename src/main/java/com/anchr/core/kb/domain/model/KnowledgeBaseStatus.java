package com.anchr.core.kb.domain.model;

import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * Lifecycle status for a knowledge base.
 */
@Getter
public enum KnowledgeBaseStatus {
    ACTIVE("0", "active"),
    ARCHIVED("1", "archived"),
    DELETED("2", "deleted")
    ;
    private final String code;
    private final String name;

    KnowledgeBaseStatus(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static KnowledgeBaseStatus fetchByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        for (KnowledgeBaseStatus value : KnowledgeBaseStatus.values()) {
            if (code.equals(value.getCode())) {
                return value;
            }
        }
        return null;
    }
}
