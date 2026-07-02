package com.anchr.core.auth.infrastructure;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.infrastructure.AccessTokenInterceptor;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenInterceptorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AccessTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AccessTokenInterceptor(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void preHandle_shouldPassThrough_whenHandlerIsNotHandlerMethod() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void preHandle_shouldAllow_whenMethodDoesNotRequireAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "publicApi");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContextHolder.get().accessTokenHash()).isNull();
    }

    @Test
    void preHandle_shouldAllow_whenTokenMatchesOnRequireAuthMethod() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Access-Token", "token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "protectedApi");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "token-123"))
                .thenReturn("{\"role\":\"OWNER\",\"createdAt\":1}");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContextHolder.get().accessTokenHash())
                .isNotBlank()
                .isNotEqualTo("token-123");
        assertThat(UserContextHolder.get().role()).isEqualTo("OWNER");
    }

    @Test
    void preHandle_shouldRejectWith401_whenTokenNotFoundInRedis() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Access-Token", "unknown-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "protectedApi");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "unknown-token")).thenReturn(null);

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("token is invalid or expired");
    }

    @Test
    void preHandle_shouldRejectWith401_whenTokenMissingOnRequireAuthMethod() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "protectedApi");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("token is invalid or expired");
    }

    @Test
    void preHandle_shouldRejectWith403_whenGuestAccessesOwnerOnlyEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Access-Token", "guest-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "ownerOnlyApi");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "guest-token"))
                .thenReturn("{\"role\":\"GUEST\",\"createdAt\":1}");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("访客权限");
    }

    @Test
    void preHandle_shouldAllow_whenGuestAccessesGuestAllowedEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Access-Token", "guest-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "guestAllowedApi");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX + "guest-token"))
                .thenReturn("{\"role\":\"GUEST\",\"createdAt\":1}");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContextHolder.get().role()).isEqualTo("GUEST");
    }

    @SuppressWarnings("unused")
    private static final class DummyController {
        public void publicApi() {
        }

        @RequireAuth
        public void protectedApi() {
        }

        @RequireAuth
        public void ownerOnlyApi() {
        }

        @RequireAuth(roles = {"OWNER", "GUEST"})
        public void guestAllowedApi() {
        }
    }
}
