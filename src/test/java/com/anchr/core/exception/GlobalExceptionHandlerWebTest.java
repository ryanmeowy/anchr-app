package com.anchr.core.exception;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerWebTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldMapIllegalArgumentTo400() throws Exception {
        mockMvc.perform(get("/test/illegal"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("bad request"));
    }

    @Test
    void shouldMapMissingParamTo400() throws Exception {
        mockMvc.perform(get("/test/missing-param"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request parameters."));
    }

    @Test
    void shouldMapUnexpectedTo500AndReturnErrorId() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal error, please try again later."))
                .andExpect(jsonPath("$.errorId").isString());
    }

    @Test
    void shouldMapBusinessExceptionToItsOwnCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Resource conflict."));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/illegal")
        public String illegal() {
            throw new IllegalArgumentException("bad request");
        }

        @GetMapping("/test/missing-param")
        public String missing(@RequestParam("q") String q) {
            return q;
        }

        @GetMapping("/test/unexpected")
        public String unexpected() {
            throw new RuntimeException("boom");
        }

        @GetMapping("/test/business")
        public String business() {
            throw new BusinessException(ApiError.CONFLICT);
        }
    }
}
