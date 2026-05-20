
package com.anchr.core.common.model;

import com.anchr.core.common.exception.ApiError;
import lombok.Data;

import java.io.Serializable;

/**
 * Unified API response result encapsulation
 * @param <T> Business data type
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Data
public class Result<T> implements Serializable {

    private int code;

    private String message;

    private T data;

    private long timestamp;

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
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("Success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(ApiError error) {
        return error(error.getCode(), error.getMessage());
    }

    public static <T> Result<T> error(int code, String message, String errorId) {
        Result<T> result = error(code, message);
        result.setErrorId(errorId);
        return result;
    }

    public static <T> Result<T> error(ApiError error, String errorId) {
        return error(error.getCode(), error.getMessage(), errorId);
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
