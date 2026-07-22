package com.anchr.core.common.exception;

import lombok.Getter;

/**
 * Unified API error dictionary for stable code/message mapping.
 */
@Getter
public enum ApiError {
    INVALID_REQUEST(400, "Invalid request parameters."),
    UPLOAD_TOO_LARGE(400, "The uploaded file is too large, please upload a file within 10MB."),
    UNAUTHORIZED(401, "Unauthorized access"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Resource not found."),
    AUTH_TOKEN_INVALID(401, "The token is invalid or expired, please contact the administrator to refresh it"),
    AUTH_ROLE_FORBIDDEN(403, "CURRENT ROLE ACCESS DENIED"),
    AUTH_ADMIN_SECRET_MISSING(500, "Internal error"),
    AUTH_STS_FETCH_FAILED(500, "Failed to fetch STS token"),
    EMBEDDING_FAILED(500, "Failed to generate image embedding, please retry later."),
    EMBEDDING_RESULT_EMPTY(500, "Embedding result is empty, please retry later."),
    TEXT_PARSER_UNAVAILABLE(500, "No available text parser"),
    TEXT_PARSE_FAILED(500, "Text parse failed"),
    CONVERSATION_SESSION_NOT_FOUND(404, "Conversation session not found"),
    KNOWLEDGE_BASE_NOT_FOUND(404, "Knowledge base not found"),
    DOCUMENT_NOT_FOUND(404, "Document not found"),
    DOCUMENT_PREVIEW_NOT_AVAILABLE(422, "Document preview is not available"),
    INGESTION_TASK_NOT_FOUND(404, "Ingestion task not found"),
    SEGMENT_NOT_FOUND(404, "Segment not found"),
    PROVIDER_UNAVAILABLE(503, "Provider unavailable.", true),
    INGEST_TASK_ITEM_NOT_FOUND(404, "Task item not found"),
    INGEST_RETRY_ONLY_FAILED(409, "Only FAILED item can be retried"),
    INGEST_NO_FAILED_ITEMS(409, "No FAILED items to retry"),
    IDEMPOTENCY_KEY_REUSED(409, "The idempotency key was already used for a different request."),
    SEARCH_BACKEND_UNAVAILABLE(500, "Search backend unavailable"),
    PREVIEW_URL_SIGN_FAILED(500, "Failed to sign preview URL"),
    INTERNAL_ERROR(500, "Internal error, please try again later."),
    INVALID_API_KEY(500, "Invalid API KEY");

    private final int code;
    private final String message;
    private final boolean retryable;

    ApiError(int code, String message) {
        this(code, message, false);
    }

    ApiError(int code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }
}
