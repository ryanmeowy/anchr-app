package com.anchr.core.search.escli.domain;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Validator for index/path parameters in ES CLI read-only APIs.
 */
public final class EsCliIndexValidator {

    private static final Pattern INDEX_PATTERN = Pattern.compile("^[a-z0-9._-]+$");
    private static final Pattern INDEX_PATTERN_WITH_WILDCARD = Pattern.compile("^[a-z0-9._*-]+$");

    private EsCliIndexValidator() {
    }

    public static void validateIndex(String index) {
        if (!StringUtils.hasText(index) || !INDEX_PATTERN.matcher(index).matches()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "Invalid index name");
        }
    }

    public static void validatePattern(String pattern) {
        if (!StringUtils.hasText(pattern)) {
            return;
        }
        if (!INDEX_PATTERN_WITH_WILDCARD.matcher(pattern).matches()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "Invalid index pattern");
        }
    }

    public static void validatePageAndSize(int page, int size) {
        if (page < 1) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "page must be >= 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "size must be between 1 and 100");
        }
    }
}
