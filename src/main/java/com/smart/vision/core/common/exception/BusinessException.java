package com.smart.vision.core.common.exception;

import lombok.Getter;

/**
 * Unified business exception carrying an explicit API error code.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ApiError error;
    private final int code;

    public BusinessException(ApiError error) {
        super(error.getMessage());
        this.error = error;
        this.code = error.getCode();
    }

    public BusinessException(ApiError error, String message) {
        super(message);
        this.error = error;
        this.code = error.getCode();
    }

    public BusinessException(ApiError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
        this.code = error.getCode();
    }

    public BusinessException(ApiError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
        this.code = error.getCode();
    }
}
