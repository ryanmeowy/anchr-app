package com.anchr.core.common.exception;

import com.anchr.core.common.model.ErrorResponseMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * Global API exception handler for consistent error responses and observability.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ApiErrorResponseWriter errorResponseWriter;
    private final UploadCleanupPolicy uploadCleanupPolicy;

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException e, HttpServletRequest request,
                                        HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        String fallback = e.getError() == null ? ApiError.INTERNAL_ERROR.getMessage() : e.getError().getMessage();
        ApiError error = e.getError() == null ? ApiError.INTERNAL_ERROR : e.getError();
        HttpStatus status = resolveStatus(error);
        if (status.is5xxServerError()) {
            log.error("Business exception, traceId={}, errorCode={}, status={}, message={}",
                    traceId, error.name(), status.value(), e.getMessage(), e);
        } else {
            log.warn("Business exception, traceId={}, errorCode={}, status={}, message={}",
                    traceId, error.name(), status.value(), e.getMessage());
        }
        errorResponseWriter.write(response, status, error, safeMessage(e.getMessage(), fallback), traceId,
                uploadCleanupPolicy.forBusinessError(request, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void handleIllegalArgument(IllegalArgumentException e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Illegal argument, traceId={}, message={}", traceId, e.getMessage(), e);
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST,
                safeMessage(e.getMessage(), ApiError.INVALID_REQUEST.getMessage()), traceId,
                ErrorResponseMetadata.conservative());
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            BindException.class
    })
    public void handleBadRequest(Exception e, HttpServletRequest request, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Bad request, traceId={}, exceptionType={}, message={}",
                traceId, e.getClass().getSimpleName(), e.getMessage(), e);
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST,
                ApiError.INVALID_REQUEST.getMessage(), traceId,
                uploadCleanupPolicy.forPreControllerRejection(request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public void handleConstraintViolation(ConstraintViolationException e, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Constraint violation, traceId={}, message={}", traceId, e.getMessage(), e);
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST,
                ApiError.INVALID_REQUEST.getMessage(), traceId, ErrorResponseMetadata.conservative());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public void handleUploadTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request,
                                     HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Upload too large, traceId={}, message={}", traceId, e.getMessage(), e);
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, ApiError.UPLOAD_TOO_LARGE,
                ApiError.UPLOAD_TOO_LARGE.getMessage(), traceId,
                uploadCleanupPolicy.forPreControllerRejection(request));
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
        errorResponseWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR, ApiError.INTERNAL_ERROR,
                ApiError.INTERNAL_ERROR.getMessage(), errorId, ErrorResponseMetadata.conservative());
    }

    private String safeMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }

    private HttpStatus resolveStatus(ApiError error) {
        HttpStatus status = HttpStatus.resolve(error.getCode());
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }
}
