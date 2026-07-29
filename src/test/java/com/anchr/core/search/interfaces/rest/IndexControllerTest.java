package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.GlobalExceptionHandler;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import com.anchr.core.search.application.SegmentIndexManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class IndexControllerTest {

    @Mock private SegmentIndexManager segmentIndexManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new IndexController(segmentIndexManager))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new ApiErrorResponseWriter(objectMapper), new UploadCleanupPolicy()))
                .build();
    }

    @Test
    void blankTaskIdReturnsHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/index/rebuild/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void rejectedRetryReturnsHttp409() throws Exception {
        when(segmentIndexManager.retryCreate()).thenReturn(false);

        mockMvc.perform(post("/api/v1/index/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("INDEX_OPERATION_CONFLICT"));
    }

    @Test
    void prepareWithoutRequiredRebuildRemainsSuccessful() throws Exception {
        when(segmentIndexManager.prepareRebuild()).thenReturn(null);

        mockMvc.perform(post("/api/v1/index/rebuild/prepare"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
