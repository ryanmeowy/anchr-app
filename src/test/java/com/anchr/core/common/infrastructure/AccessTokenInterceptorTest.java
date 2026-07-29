package com.anchr.core.common.infrastructure;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import com.anchr.core.ingestion.interfaces.rest.KnowledgeBaseIngestionController;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateRequestDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenInterceptorTest {

    private ObjectMapper objectMapper;
    private Map<String, String> tokenValues;
    private AccessTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tokenValues = new HashMap<>();
        StringRedisTemplate redisTemplate = new StubStringRedisTemplate(tokenValues);
        UploadCleanupPolicy cleanupPolicy = new UploadCleanupPolicy();
        interceptor = new AccessTokenInterceptor(
                redisTemplate,
                objectMapper,
                new ApiErrorResponseWriter(objectMapper),
                cleanupPolicy);
    }

    @Test
    void shouldAllowUploadCleanupWhenAuthenticationRejectsCreateBeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/kbs/kb-1/ingestion-tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, ingestionCreateHandler());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.AUTH_TOKEN_INVALID.name());
        assertThat(body.path("requestAccepted").asBoolean()).isFalse();
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isTrue();
    }

    @Test
    void shouldKeep403BehaviorAndAllowCleanupBeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/kbs/kb-1/ingestion-tasks");
        request.addHeader("X-Access-Token", "guest-token");
        tokenValues.put(TOKEN_CACHE_PREFIX + "guest-token", "{\"role\":\"GUEST\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, ingestionCreateHandler());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.AUTH_ROLE_FORBIDDEN.name());
        assertThat(body.path("requestAccepted").asBoolean()).isFalse();
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isTrue();
    }

    @Test
    void shouldNotGrantCleanupForAnUnannotatedEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/kbs/kb-1/ingestion-tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, ingestionListHandler());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.path("uploadCleanupAllowed").asBoolean()).isFalse();
        assertThat(body.has("requestAccepted")).isFalse();
    }

    private HandlerMethod ingestionCreateHandler() throws NoSuchMethodException {
        Method method = KnowledgeBaseIngestionController.class.getMethod(
                "createTask", String.class, IngestionTaskCreateRequestDTO.class);
        return new HandlerMethod(controller(), method);
    }

    private HandlerMethod ingestionListHandler() throws NoSuchMethodException {
        Method method = KnowledgeBaseIngestionController.class.getMethod(
                "listTasks", String.class,
                com.anchr.core.ingestion.domain.model.IngestionTaskStatus.class, int.class);
        return new HandlerMethod(controller(), method);
    }

    private KnowledgeBaseIngestionController controller() {
        return new KnowledgeBaseIngestionController(null);
    }

    private static final class StubStringRedisTemplate extends StringRedisTemplate {

        private final ValueOperations<String, String> valueOperations;

        @SuppressWarnings("unchecked")
        private StubStringRedisTemplate(Map<String, String> values) {
            valueOperations = (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        if ("get".equals(method.getName()) && args != null && args.length == 1) {
                            return values.get(args[0]);
                        }
                        if ("toString".equals(method.getName())) {
                            return "StubValueOperations";
                        }
                        return null;
                    });
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOperations;
        }
    }
}
