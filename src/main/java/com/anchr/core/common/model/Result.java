
package com.anchr.core.common.model;

import com.anchr.core.common.exception.ApiError;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * Unified API response result encapsulation
 * @param <T> Business data type
 *
 */
@Data
public class Result<T> implements Serializable {

    private int code;

    private String message;

    private String errorCode;

    private T data;

    private long timestamp;

    private String traceId;

    private Map<String, Object> details;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean retryable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean requestAccepted;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean uploadCleanupAllowed;

    /**
     * Correlation id for troubleshooting error responses.
     */
    private String errorId;

    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return success(200, data);
    }

    public static <T> Result<T> success(int code, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage("Success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setErrorCode(String.valueOf(code));
        result.setDetails(Map.of());
        result.setUploadCleanupAllowed(false);
        return result;
    }

    public static <T> Result<T> error(ApiError error) {
        Result<T> result = error(error.getCode(), error.getMessage());
        result.setErrorCode(error.name());
        result.setRetryable(error.isRetryable());
        return result;
    }

    public static <T> Result<T> error(int code, String message, String errorId) {
        Result<T> result = error(code, message);
        result.setErrorId(errorId);
        result.setTraceId(errorId);
        return result;
    }

    public static <T> Result<T> error(ApiError error, String errorId) {
        Result<T> result = error(error);
        result.setErrorId(errorId);
        result.setTraceId(errorId);
        return result;
    }

    public static <T> Result<T> error(ApiError error, String message, String traceId) {
        Result<T> result = error(error.getCode(), message);
        result.setErrorCode(error.name());
        result.setRetryable(error.isRetryable());
        result.setErrorId(traceId);
        result.setTraceId(traceId);
        return result;
    }

    public static <T> Result<T> error(ApiError error, String message, String traceId,
                                      ErrorResponseMetadata metadata) {
        Result<T> result = error(error, message, traceId);
        if (metadata != null) {
            result.setRequestAccepted(metadata.requestAccepted());
            result.setUploadCleanupAllowed(metadata.uploadCleanupAllowed());
        }
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
