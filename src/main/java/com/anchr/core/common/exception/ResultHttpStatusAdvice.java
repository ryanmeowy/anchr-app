package com.anchr.core.common.exception;

import com.anchr.core.common.model.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Keeps the transport status aligned with {@link ApiError}-backed {@link Result} bodies returned
 * directly by a controller. Legacy free-form errors keep their existing transport behavior until
 * their business-specific error codes are defined.
 */
@RestControllerAdvice
public class ResultHttpStatusAdvice implements ResponseBodyAdvice<Result<?>> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Result<?> beforeBodyWrite(Result<?> body, MethodParameter returnType,
                                     MediaType selectedContentType,
                                     Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                     ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || (body.getCode() >= 200 && body.getCode() < 300)) {
            return body;
        }
        ApiError error = resolveApiError(body.getErrorCode());
        if (error == null || error.getCode() != body.getCode()) {
            return body;
        }
        HttpStatus status = HttpStatus.resolve(error.getCode());
        if (status != null) {
            response.setStatusCode(status);
        }
        return body;
    }

    private ApiError resolveApiError(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        try {
            return ApiError.valueOf(errorCode);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
