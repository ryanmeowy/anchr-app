package com.anchr.core.common.exception;

import com.anchr.core.common.model.ErrorResponseMetadata;
import com.anchr.core.common.model.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Writes the same JSON error envelope for controller advice and interceptor rejections.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpStatus status, ApiError error,
                      String message, String traceId, ErrorResponseMetadata metadata) {
        if (response.isCommitted()) {
            log.debug("Skip API error response because it is already committed, traceId={}", traceId);
            return;
        }
        Result<Void> result = Result.error(error, message, traceId, metadata);
        String payload = serialize(result, status.value());
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write(payload);
        } catch (Exception e) {
            log.warn("Failed to write API error response, traceId={}", traceId, e);
        }
    }

    private String serialize(Result<Void> result, int httpStatus) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize API error response, traceId={}", result.getTraceId(), e);
            return "{\"code\":" + httpStatus + ",\"message\":\"Request failed\"}";
        }
    }
}
