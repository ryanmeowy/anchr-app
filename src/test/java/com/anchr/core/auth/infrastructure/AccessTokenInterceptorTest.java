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
        when(valueOperations.get(TOKEN_CACHE_PREFIX)).thenReturn("token-123");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContextHolder.get().accessTokenHash())
                .isNotBlank()
                .isNotEqualTo("token-123");
    }

    @Test
    void preHandle_shouldRejectWith401_whenTokenMismatchOnRequireAuthMethod() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Access-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), "protectedApi");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX)).thenReturn("server-token");

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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_CACHE_PREFIX)).thenReturn("server-token");

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("token is invalid or expired");
    }

    @SuppressWarnings("unused")
    private static final class DummyController {
        public void publicApi() {
        }

        @RequireAuth
        public void protectedApi() {
        }
    }
}
