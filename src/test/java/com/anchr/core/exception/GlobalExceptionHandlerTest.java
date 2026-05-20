package com.anchr.core.exception;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.exception.GlobalExceptionHandler;
import com.anchr.core.common.model.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgument_shouldReturn400WithMessage() {
        Result<Void> result = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("bad input");
    }

    @Test
    void handleBusinessException_shouldUseCodeAndMessageFromException() {
        Result<Void> result = handler.handleBusinessException(new BusinessException(ApiError.CONFLICT));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("Resource conflict.");
    }

    @Test
    void handleUnexpected_shouldReturn500WithoutLeakingInternalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/vision/search");

        Result<Void> result = handler.handleUnexpected(new RuntimeException("db timeout"), request);

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMessage()).isEqualTo("Internal error, please try again later.");
        assertThat(result.getErrorId()).isNotBlank();
    }
}
