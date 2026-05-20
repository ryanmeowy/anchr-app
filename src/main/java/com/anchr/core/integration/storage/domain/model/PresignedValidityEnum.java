package com.anchr.core.integration.storage.domain.model;

import lombok.Getter;

/**
 * Presigned URL validity
 *
 * @author Ryan
 * @since 2025/12/19
 */
@Getter
public enum PresignedValidityEnum {
    SHORT_TERM_VALIDITY(5 * 60 * 1_000L, "Short term, used for vector calculation and OCR scenarios"),
    MEDIUM_TERM_VALIDITY(15 * 60 * 1_000L, "Medium term, used for AI-generated copywriting scenarios"),
    LONG_TERM_VALIDITY(7 * 24 * 60 * 60 * 1_000L, "Long term, used for page display scenarios");

    PresignedValidityEnum(Long validity, String desc) {
        this.validity = validity;
        this.desc = desc;
    }

    /**
     * Validity period, unit: milliseconds
     */
    private final Long validity;
    private final String desc;
}
