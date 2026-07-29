package com.anchr.core.common.exception;

import com.anchr.core.common.model.ErrorResponseMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;

/**
 * Resolves explicit upload cleanup permissions without inferring acceptance from an HTTP status.
 */
@Component
public class UploadCleanupPolicy {

    public ErrorResponseMetadata forBusinessError(HttpServletRequest request, ApiError error) {
        UploadCleanupContract contract = findContract(request);
        if (contract != null && error != null
                && Arrays.asList(contract.safeBusinessErrors()).contains(error)) {
            return ErrorResponseMetadata.rejectedWithUploadCleanup();
        }
        return ErrorResponseMetadata.conservative();
    }

    public ErrorResponseMetadata forPreControllerRejection(HttpServletRequest request) {
        return findContract(request) == null
                ? ErrorResponseMetadata.conservative()
                : ErrorResponseMetadata.rejectedWithUploadCleanup();
    }

    public ErrorResponseMetadata forPreControllerRejection(Object handler) {
        return findContract(handler) == null
                ? ErrorResponseMetadata.conservative()
                : ErrorResponseMetadata.rejectedWithUploadCleanup();
    }

    private UploadCleanupContract findContract(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return findContract(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE));
    }

    private UploadCleanupContract findContract(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        return handlerMethod.getMethodAnnotation(UploadCleanupContract.class);
    }
}
