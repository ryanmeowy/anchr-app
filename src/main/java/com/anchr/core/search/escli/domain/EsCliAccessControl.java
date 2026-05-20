package com.anchr.core.search.escli.domain;

import com.anchr.core.common.config.VectorConfig;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Index-level access control for ES CLI read-only APIs.
 */
@Component
@RequiredArgsConstructor
public class EsCliAccessControl {

    private final EsCliAccessConfig accessConfig;
    private final VectorConfig vectorConfig;

    public void assertIndexAllowed(String index) {
        if (!isAllowed(index)) {
            throw new BusinessException(ApiError.FORBIDDEN, "Index access denied");
        }
    }

    public boolean isAllowed(String index) {
        if (!StringUtils.hasText(index)) {
            return false;
        }
        String target = index.trim();
        for (String pattern : resolveAllowedPatterns()) {
            if (matches(pattern, target)) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveAllowedPatterns() {
        List<String> configured = accessConfig.getAllowedIndexPatterns();
        if (configured != null && !configured.isEmpty()) {
            return configured.stream().filter(StringUtils::hasText).map(String::trim).toList();
        }

        List<String> defaults = new ArrayList<>();
        addIfPresent(defaults, vectorConfig.getReadAlias());
        addIfPresent(defaults, vectorConfig.getWriteAlias());
        addIfPresent(defaults, vectorConfig.getPhysicalIndexName());
        return defaults;
    }

    private void addIfPresent(List<String> list, String value) {
        if (StringUtils.hasText(value)) {
            list.add(value.trim());
        }
    }

    private boolean matches(String wildcardPattern, String target) {
        if (!StringUtils.hasText(wildcardPattern)) {
            return false;
        }
        String regex = wildcardPattern.trim().replace(".", "\\.").replace("*", ".*");
        return Pattern.matches("^" + regex + "$", target);
    }
}
