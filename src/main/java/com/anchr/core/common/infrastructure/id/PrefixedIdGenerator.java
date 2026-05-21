package com.anchr.core.common.infrastructure.id;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Generates readable business ids with stable domain prefixes.
 */
@Component
@RequiredArgsConstructor
public class PrefixedIdGenerator {

    private final IdGen idGen;

    public String nextId(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "id prefix cannot be blank.");
        }
        return prefix.trim() + "_" + idGen.nextId();
    }
}
