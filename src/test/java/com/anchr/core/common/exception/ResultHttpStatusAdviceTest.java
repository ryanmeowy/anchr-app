package com.anchr.core.common.exception;

import com.anchr.core.common.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResultHttpStatusAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ResultController())
                .setControllerAdvice(new ResultHttpStatusAdvice())
                .build();
    }

    @Test
    void shouldAlignDirectApiErrorResultWithItsHttpStatus() throws Exception {
        mockMvc.perform(get("/result/error"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value(ApiError.FORBIDDEN.name()))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.uploadCleanupAllowed").value(false))
                .andExpect(jsonPath("$.requestAccepted").doesNotExist());
    }

    @Test
    void shouldLeaveSuccessStatusAndShapeUntouched() throws Exception {
        mockMvc.perform(get("/result/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(jsonPath("$.retryable").doesNotExist())
                .andExpect(jsonPath("$.requestAccepted").doesNotExist())
                .andExpect(jsonPath("$.uploadCleanupAllowed").doesNotExist());
    }

    @Test
    void shouldPreserveLegacyTransportStatusForFreeFormErrors() throws Exception {
        mockMvc.perform(get("/result/legacy-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.errorCode").value("500"));
    }

    @RestController
    private static class ResultController {

        @GetMapping("/result/error")
        Result<Void> error() {
            return Result.error(ApiError.FORBIDDEN);
        }

        @GetMapping("/result/success")
        Result<String> success() {
            return Result.success("ok");
        }

        @GetMapping("/result/legacy-error")
        Result<Void> legacyError() {
            return Result.error("legacy error");
        }
    }
}
