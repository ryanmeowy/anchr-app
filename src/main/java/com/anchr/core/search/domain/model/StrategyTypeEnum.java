
package com.anchr.core.search.domain.model;

import lombok.Getter;

/**
 * Search strategy type enumeration
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Getter
public enum StrategyTypeEnum {
    /**
     * Hybrid search (default): vector similarity + OCR text matching
     * Use case: general search, searching for both visual content and text
     */
    HYBRID("0", "Hybrid search (default)"),

    /**
     * Pure vector search
     * Use case: text-to-image search (describing visual content), ignoring OCR
     */
    VECTOR_ONLY("1", "Pure vector search"),

    /**
     * Pure text search (BM25)
     * Use case: searching only for text within images
     */
    TEXT_ONLY("2", "Pure text search (BM25)"),

    /**
     * Image-to-image search
     * Use case: user uploads an image to search for similar images
     */
    IMAGE_TO_IMAGE("3", "Image-to-image search");

    private final String code;
    private final String desc;

    StrategyTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static StrategyTypeEnum getByCode(String code) {
        for (StrategyTypeEnum value : StrategyTypeEnum.values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
