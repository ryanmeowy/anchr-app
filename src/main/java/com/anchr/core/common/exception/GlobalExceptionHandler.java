package com.anchr.core.common.exception;

import com.anchr.core.common.model.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.UUID;

/**
 * Global API exception handler for consistent error responses and observability.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        String fallback = e.getError() == null ? ApiError.INTERNAL_ERROR.getMessage() : e.getError().getMessage();
        ApiError error = e.getError() == null ? ApiError.INTERNAL_ERROR : e.getError();
        log.error("Business exception, traceId={}, errorCode={}, message={}",
                traceId, error.name(), e.getMessage(), e);
        writeJsonError(response, HttpStatus.BAD_REQUEST, error,
                safeMessage(e.getMessage(), fallback), traceId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void handleIllegalArgument(IllegalArgumentException e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Illegal argument, traceId={}, message={}", traceId, e.getMessage(), e);
        writeJsonError(response, HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST,
                safeMessage(e.getMessage(), ApiError.INVALID_REQUEST.getMessage()), traceId);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    public void handleBadRequest(Exception e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Bad request, traceId={}, exceptionType={}, message={}",
                traceId, e.getClass().getSimpleName(), e.getMessage(), e);
        writeJsonError(response, HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST, traceId);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public void handleUploadTooLarge(MaxUploadSizeExceededException e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Upload too large, traceId={}, message={}", traceId, e.getMessage(), e);
        writeJsonError(response, HttpStatus.BAD_REQUEST, ApiError.UPLOAD_TOO_LARGE, traceId);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e, HttpServletRequest request) {
        String path = request == null ? "unknown" : request.getRequestURI();
        log.debug("Client disconnected before response completed, path={}, message={}", path, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception e, HttpServletRequest request, HttpServletResponse response) {
        String path = request == null ? "unknown" : request.getRequestURI();
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception, errorId={}, path={}, message={}", errorId, path, e.getMessage(), e);
        writeJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR, ApiError.INTERNAL_ERROR, errorId);
    }

    private String safeMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, ApiError error, String traceId) {
        writeJsonErrorInternal(response, status.value(), Result.error(error, traceId));
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, ApiError error,
                                String message, String traceId) {
        writeJsonErrorInternal(response, status.value(), Result.error(error, message, traceId));
    }

    /**
     * Write error response directly to {@link HttpServletResponse} to bypass Spring's content
     * negotiation. This is necessary for endpoints that declare a constrained
     * {@code produces} type (e.g. {@code text/event-stream} for SSE) where the client's
     * {@code Accept} header would otherwise prevent JSON serialization of the error body,
     * causing an {@code HttpMediaTypeNotAcceptableException}.
     */
    private void writeJsonErrorInternal(HttpServletResponse response, int httpStatus, Result<Void> result) {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write(objectMapper.writeValueAsString(result));
        } catch (Exception ex) {
            log.warn("Failed to serialize error response, fallback to minimal json", ex);
            try {
                response.getWriter().write(
                        "{\"code\":" + result.getCode() + ",\"message\":\"" + result.getMessage() + "\"}");
            } catch (Exception ignored) {
                // response already committed or stream closed
            }
        }
    }
}
