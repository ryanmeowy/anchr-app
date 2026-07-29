package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.acl.AuthStorageAcl;
import com.anchr.core.auth.application.model.AuthStorageCredential;
import com.anchr.core.auth.interfaces.rest.dto.TokenValidationDTO;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.model.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private AuthController controller;
    private ValueOperations<String, String> valueOperations;
    private AuthStorageAcl authStorageAcl;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        authStorageAcl = mock(AuthStorageAcl.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        controller = new AuthController(
                redisTemplate,
                authStorageAcl,
                new ObjectMapper()
        );
    }

    @Test
    void shouldValidateAdminTokenAsAdmin() {
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "admin-token"))
                .thenReturn("{\"role\":\"ADMIN\",\"createdAt\":1}");

        Result<TokenValidationDTO> result = controller.validateToken(" admin-token ");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().isValid()).isTrue();
        assertThat(result.getData().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void shouldReturnUserAndGuestRoles() {
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "user-token"))
                .thenReturn("{\"role\":\"USER\"}");
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "guest-token"))
                .thenReturn("{\"role\":\"GUEST\"}");

        assertThat(controller.validateToken("user-token").getData().getRole()).isEqualTo("USER");
        assertThat(controller.validateToken("guest-token").getData().getRole()).isEqualTo("GUEST");
    }

    @Test
    void shouldRejectMissingOrUnknownToken() {
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "unknown-token")).thenReturn(null);

        Result<TokenValidationDTO> missing = controller.validateToken(" ");
        Result<TokenValidationDTO> unknown = controller.validateToken("unknown-token");

        assertThat(missing.getCode()).isEqualTo(ApiError.AUTH_TOKEN_INVALID.getCode());
        assertThat(missing.getErrorCode()).isEqualTo(ApiError.AUTH_TOKEN_INVALID.name());
        assertThat(unknown.getCode()).isEqualTo(ApiError.AUTH_TOKEN_INVALID.getCode());
    }

    @Test
    void shouldRejectMalformedTokenDataAndUnsupportedRole() {
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "malformed-token")).thenReturn("not-json");
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "unsupported-token"))
                .thenReturn("{\"role\":\"SUPERUSER\"}");

        assertThat(controller.validateToken("malformed-token").getCode())
                .isEqualTo(ApiError.AUTH_TOKEN_INVALID.getCode());
        assertThat(controller.validateToken("unsupported-token").getCode())
                .isEqualTo(ApiError.AUTH_TOKEN_INVALID.getCode());
    }

    @Test
    void shouldKeepExistingStsResponseFields() {
        when(authStorageAcl.issueUploadCredential()).thenReturn(
                new AuthStorageCredential(
                        "https://oss", "bucket", "cn-test", "uploads/",
                        "temp-ak", "temp-sk", "token", "2026-07-29T19:00:00Z"));

        Result<java.util.Map<String, Object>> result = controller.getStsToken();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsExactly(
                org.assertj.core.api.Assertions.entry("endpoint", "https://oss"),
                org.assertj.core.api.Assertions.entry("bucket", "bucket"),
                org.assertj.core.api.Assertions.entry("region", "cn-test"),
                org.assertj.core.api.Assertions.entry("prefix", "uploads/"),
                org.assertj.core.api.Assertions.entry("accessKeyId", "temp-ak"),
                org.assertj.core.api.Assertions.entry("accessKeySecret", "temp-sk"),
                org.assertj.core.api.Assertions.entry("securityToken", "token"),
                org.assertj.core.api.Assertions.entry(
                        "expiration", "2026-07-29T19:00:00Z"));
    }
}
