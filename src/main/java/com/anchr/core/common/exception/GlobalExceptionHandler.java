package com.anchr.core.common.exception;

import com.anchr.core.common.model.Result;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.UUID;

/**
 * Global API exception handler for consistent error responses and observability.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        String traceId = UUID.randomUUID().toString();
        String fallback = e.getError() == null ? ApiError.INTERNAL_ERROR.getMessage() : e.getError().getMessage();
        ApiError error = e.getError() == null ? ApiError.INTERNAL_ERROR : e.getError();
        return Result.error(error, safeMessage(e.getMessage(), fallback), traceId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        String traceId = UUID.randomUUID().toString();
        return Result.error(ApiError.INVALID_REQUEST,
                safeMessage(e.getMessage(), ApiError.INVALID_REQUEST.getMessage()),
                traceId);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    public Result<Void> handleBadRequest(Exception e) {
        String traceId = UUID.randomUUID().toString();
        return Result.error(ApiError.INVALID_REQUEST, traceId);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        String traceId = UUID.randomUUID().toString();
        return Result.error(ApiError.UPLOAD_TOO_LARGE, traceId);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        String path = request == null ? "unknown" : request.getRequestURI();
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception, errorId={}, path={}, message={}", errorId, path, e.getMessage(), e);
        return Result.error(ApiError.INTERNAL_ERROR, errorId);
    }

    private String safeMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }
}
