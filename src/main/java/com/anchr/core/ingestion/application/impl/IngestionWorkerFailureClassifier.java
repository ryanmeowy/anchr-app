package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.springframework.util.StringUtils;

final class IngestionWorkerFailureClassifier {
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    Failure classify(RuntimeException exception) {
        if (exception instanceof IngestionWorkerInterruptedException) {
            return new Failure(
                    ApiError.INTERNAL_ERROR,
                    "文档处理线程被中断，请重新执行。");
        }
        if (exception instanceof BusinessException businessFailure) {
            return new Failure(
                    businessFailure.getError(),
                    safeMessage(businessFailure.getError(), businessFailure.getMessage()));
        }
        ApiError error = exception instanceof IngestionEmbeddingCallException
                ? ApiError.EMBEDDING_FAILED : ApiError.INTERNAL_ERROR;
        return new Failure(error, safeMessage(error, exception.getMessage()));
    }

    private String safeMessage(ApiError error, String message) {
        String value = StringUtils.hasText(message) ? message : error.getMessage();
        if (!StringUtils.hasText(value)) return null;
        return value.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? value : value.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    record Failure(ApiError error, String message) {
    }
}

final class IngestionWorkerInterruptedException extends RuntimeException {
    IngestionWorkerInterruptedException(Throwable cause) {
        super(cause);
    }
}

final class IngestionEmbeddingCallException extends RuntimeException {
    IngestionEmbeddingCallException(Throwable cause) {
        super(cause == null ? null : cause.getMessage(), cause);
    }
}
