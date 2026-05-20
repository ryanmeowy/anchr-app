package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.common.model.Result;
import com.anchr.core.auth.application.OssService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthApiControllerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private OssService ossService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthApiController(redisTemplate, ossService);
        ReflectionTestUtils.setField(controller, "adminSecret", "top-secret");
    }

    @Test
    void refreshToken_shouldRejectWhenSecretIsInvalid() {
        Result<String> result = controller.refreshToken("wrong-secret", null);

        assertThat(result.getCode()).isEqualTo(403);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void refreshToken_shouldStoreGivenCodeWhenSecretIsValid() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Result<String> result = controller.refreshToken("top-secret", "654321");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo("654321");
        verify(valueOperations).set(TOKEN_CACHE_PREFIX, "654321", 12, TimeUnit.HOURS);
    }

    @Test
    void cleanToken_shouldRejectWhenSecretIsInvalid() {
        Result<Void> result = controller.cleanToken("wrong-secret");

        assertThat(result.getCode()).isEqualTo(403);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void cleanToken_shouldDeleteWhenSecretIsValid() {
        Result<Void> result = controller.cleanToken("top-secret");

        assertThat(result.getCode()).isEqualTo(200);
        verify(redisTemplate).delete(TOKEN_CACHE_PREFIX);
    }

    @Test
    void refreshToken_shouldReturnInternalErrorWhenAdminSecretMissing() {
        ReflectionTestUtils.setField(controller, "adminSecret", null);

        Result<String> result = controller.refreshToken("any-secret", null);

        assertThat(result.getCode()).isEqualTo(500);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void cleanToken_shouldReturnInternalErrorWhenAdminSecretMissing() {
        ReflectionTestUtils.setField(controller, "adminSecret", " ");

        Result<Void> result = controller.cleanToken("any-secret");

        assertThat(result.getCode()).isEqualTo(500);
        verify(redisTemplate, never()).delete(anyString());
    }
}
