package com.anchr.core.search.escli.domain;

import com.anchr.core.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsCliIndexValidatorTest {

    @Test
    void validateIndex_shouldAcceptValidName() {
        assertThatCode(() -> EsCliIndexValidator.validateIndex("smart_gallery_local_v2"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateIndex_shouldRejectWildcard() {
        assertThatThrownBy(() -> EsCliIndexValidator.validateIndex("smart_*"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid index name");
    }

    @Test
    void validatePattern_shouldAcceptWildcardPattern() {
        assertThatCode(() -> EsCliIndexValidator.validatePattern("smart_*"))
                .doesNotThrowAnyException();
    }

    @Test
    void validatePageAndSize_shouldRejectOversize() {
        assertThatThrownBy(() -> EsCliIndexValidator.validatePageAndSize(1, 101))
                .isInstanceOf(BusinessException.class)
                .hasMessage("size must be between 1 and 100");
    }
}
