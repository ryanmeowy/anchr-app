package com.anchr.core.common.exception;

import com.anchr.core.ingestion.interfaces.rest.KnowledgeBaseIngestionController;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateRequestDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private ObjectMapper objectMapper;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new GlobalExceptionHandler(
                new ApiErrorResponseWriter(objectMapper),
                new UploadCleanupPolicy());
    }

    @ParameterizedTest
    @MethodSource("businessStatuses")
    void shouldUseApiErrorAsTheHttpStatus(ApiError error, int expectedStatus) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBusinessException(
                new BusinessException(error), new MockHttpServletRequest(), response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(body.path("code").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("errorCode").asText()).isEqualTo(error.name());
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.path("errorId").asText()).isEqualTo(body.path("traceId").asText());
        assertThat(body.path("retryable").asBoolean()).isEqualTo(error.isRetryable());
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isFalse();
        assertThat(body.has("requestAccepted")).isFalse();
    }

    @Test
    void shouldAllowCleanupForExplicitlySafeBusinessRejection() throws Exception {
        MockHttpServletRequest request = ingestionCreateRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBusinessException(
                new BusinessException(ApiError.INVALID_REQUEST, "items cannot be empty."), request, response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.path("requestAccepted").asBoolean()).isFalse();
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isTrue();
    }

    @Test
    void shouldNotInferCleanupPermissionFromAnUnlistedClientError() throws Exception {
        MockHttpServletRequest request = ingestionCreateRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBusinessException(
                new BusinessException(ApiError.INGESTION_TASK_NOT_FOUND), request, response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isFalse();
        assertThat(body.has("requestAccepted")).isFalse();
    }

    @Test
    void shouldAllowCleanupWhenBindingRejectsTheUploadCreateRequest() throws Exception {
        MockHttpServletRequest request = ingestionCreateRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBadRequest(new HttpMessageNotReadableException("bad json"), request, response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.INVALID_REQUEST.name());
        assertThat(body.path("requestAccepted").asBoolean()).isFalse();
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isTrue();
    }

    @Test
    void shouldResolveTheCleanupContractDuringRealRequestValidation() throws Exception {
        MockMvc mockMvc = standaloneSetup(new KnowledgeBaseIngestionController(null))
                .setControllerAdvice(handler, new ResultHttpStatusAdvice())
                .build();

        mockMvc.perform(post("/api/v1/kbs/kb-1/ingestion-tasks")
                        .contentType("application/json")
                        .content("{\"sourceType\":\"UPLOAD\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ApiError.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.requestAccepted").value(false))
                .andExpect(jsonPath("$.uploadCleanupAllowed").value(true));
    }

    @Test
    void shouldKeepCleanupDeniedForUnexpectedFailureEvenOnUploadCreate() throws Exception {
        MockHttpServletRequest request = ingestionCreateRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleUnexpected(new IllegalStateException("after-commit failure"), request, response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isFalse();
        assertThat(body.has("requestAccepted")).isFalse();
    }

    @Test
    void shouldNotAppendJsonAfterAnSseResponseIsCommitted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");
        response.getWriter().write("event: trace\n\ndata: {}\n\n");
        response.flushBuffer();
        String committedBody = response.getContentAsString();

        handler.handleBusinessException(
                new BusinessException(ApiError.PROVIDER_UNAVAILABLE),
                new MockHttpServletRequest(), response);

        assertThat(response.getContentAsString()).isEqualTo(committedBody);
        assertThat(response.getContentType()).startsWith("text/event-stream");
    }

    @Test
    void shouldKeepConstraintViolationsCleanupConservative() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of()), response);

        JsonNode body = readBody(response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isFalse();
        assertThat(body.has("requestAccepted")).isFalse();
    }

    private MockHttpServletRequest ingestionCreateRequest() throws NoSuchMethodException {
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(
                null);
        Method method = KnowledgeBaseIngestionController.class.getMethod(
                "createTask", String.class, IngestionTaskCreateRequestDTO.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/kbs/kb-1/ingestion-tasks");
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(controller, method));
        return request;
    }

    private JsonNode readBody(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    private static Stream<Arguments> businessStatuses() {
        return Stream.of(
                Arguments.of(ApiError.UNAUTHORIZED, 401),
                Arguments.of(ApiError.FORBIDDEN, 403),
                Arguments.of(ApiError.KNOWLEDGE_BASE_NOT_FOUND, 404),
                Arguments.of(ApiError.INGEST_RETRY_ONLY_FAILED, 409),
                Arguments.of(ApiError.DOCUMENT_PREVIEW_NOT_AVAILABLE, 422),
                Arguments.of(ApiError.INTERNAL_ERROR, 500),
                Arguments.of(ApiError.PROVIDER_UNAVAILABLE, 503));
    }
}
