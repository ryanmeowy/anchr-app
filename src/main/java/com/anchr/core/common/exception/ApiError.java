package com.anchr.core.common.exception;

import lombok.Getter;

/**
 * Unified API error dictionary for stable code/message mapping.
 */
@Getter
public enum ApiError {
    INVALID_REQUEST(400, "Invalid request parameters."),
    UNAUTHORIZED(401, "Unauthorized access"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Resource not found."),
    AUTH_TOKEN_INVALID(401, "The token is invalid or expired, please contact the administrator to refresh it"),
    AUTH_ADMIN_SECRET_MISSING(500, "Internal error"),
    AUTH_STS_FETCH_FAILED(500, "Failed to fetch STS token"),
    IMAGE_UPLOAD_REQUIRED(400, "Please upload an image"),
    UPLOAD_TOO_LARGE(400, "The uploaded file is too large, please upload a file within 10MB."),
    VECTOR_COMPARE_FAILED(500, "Vector compare failed, please try again later."),
    EMBEDDING_FAILED(500, "Failed to generate image embedding, please retry later."),
    EMBEDDING_RESULT_EMPTY(500, "Embedding result is empty, please retry later."),
    TEXT_BATCH_ITEMS_REQUIRED(400, "Please provide at least one text asset item"),
    TEXT_FILE_TYPE_NOT_SUPPORTED(400, "Only PDF/TXT/MD files are supported"),
    TEXT_TASK_NOT_FOUND(404, "Text task not found"),
    TEXT_ASSET_META_NOT_FOUND(404, "Text asset metadata not found"),
    TEXT_PARSER_UNAVAILABLE(500, "No available text parser"),
    TEXT_PARSE_FAILED(500, "Text parse failed"),
    CONVERSATION_SESSION_NOT_FOUND(404, "Conversation session not found"),
    KNOWLEDGE_BASE_NOT_FOUND(404, "Knowledge base not found"),
    DOCUMENT_NOT_FOUND(404, "Document not found"),
    INGESTION_TASK_NOT_FOUND(404, "Ingestion task not found"),
    SEGMENT_NOT_FOUND(404, "Segment not found"),
    TASK_STATE_CONFLICT(409, "Current task state does not allow this operation."),
    PROVIDER_UNAVAILABLE(503, "Provider unavailable."),
    INGEST_TASK_NOT_FOUND(404, "Task not found"),
    INGEST_TASK_ITEM_NOT_FOUND(404, "Task item not found"),
    INGEST_RETRY_ONLY_FAILED(409, "Only FAILED item can be retried"),
    INGEST_TASK_RUNNING(409, "Task is running, retry after current round completes."),
    INGEST_NO_FAILED_ITEMS(409, "No FAILED items to retry"),
    INGEST_PROCESSING_RETRY_LATER(409, "Image is processing, retry later."),
    INGEST_TASK_PAYLOAD_INVALID(500, "Failed to parse task payload"),
    INGEST_TASK_PAYLOAD_SERIALIZE_FAILED(500, "Failed to serialize task payload"),
    SEARCH_BACKEND_UNAVAILABLE(500, "Search backend unavailable"),
    PREVIEW_URL_SIGN_FAILED(500, "Failed to sign preview URL"),
    IMAGE_SEARCH_FAILED(500, "Image search failed, please try again later."),
    INTERNAL_ERROR(500, "Internal error, please try again later."),
    INVALID_API_KEY(500, "Invalid API KEY");

    private final int code;
    private final String message;

    ApiError(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
