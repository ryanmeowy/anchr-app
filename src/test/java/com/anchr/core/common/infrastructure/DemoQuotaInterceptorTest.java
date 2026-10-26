package com.anchr.core.common.infrastructure;

import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class DemoQuotaInterceptorTest {

    private static final String TOKEN_HASH = "abc123";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Counters counters;
    private DemoQuotaInterceptor interceptor;
    private int tokenQuota;
    private int dailyLimit;

    @BeforeEach
    void setUp() {
        UserContextHolder.clear();
        counters = new Counters();
        tokenQuota = 3;
        dailyLimit = 10;
        rebuild();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private void rebuild() {
        DemoProperties properties = new DemoProperties(true, 5, tokenQuota, dailyLimit, 50, 10, 1, false, "", "", false);
        DemoKillSwitch killSwitch = new DemoKillSwitch(
                new CountingRedisTemplate(counters), properties);
        interceptor = new DemoQuotaInterceptor(
                new CountingRedisTemplate(counters),
                properties,
                killSwitch,
                new ApiErrorResponseWriter(objectMapper),
                new UploadCleanupPolicy());
    }

    @Test
    void unannotatedHandlerShouldNotTouchCounters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/kbs"),
                response,
                handler(new UnannotatedController(), "list"));

        assertThat(allowed).isTrue();
        assertThat(counters.values).isEmpty();
    }

    @Test
    void disabledDemoShouldNotTouchCounters() throws Exception {
        DemoProperties disabled = DemoProperties.defaults();
        interceptor = new DemoQuotaInterceptor(
                new CountingRedisTemplate(counters), disabled,
                new DemoKillSwitch(new CountingRedisTemplate(counters), disabled),
                new ApiErrorResponseWriter(objectMapper), new UploadCleanupPolicy());

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), 
                new MockHttpServletResponse(), handler(new BillableController(), "ask"));

        assertThat(allowed).isTrue();
        assertThat(counters.values).isEmpty();
    }

    @Test
    void shouldChargeOneCreditAndCountOnce() throws Exception {
        signIn("demo_a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(),
                response, handler(new BillableController(), "ask"));

        assertThat(allowed).isTrue();
        assertThat(counters.values.get(DemoConstant.QUOTA_TOKEN_PREFIX + TOKEN_HASH)).isEqualTo("1");
        assertThat(counters.values.get(dailyOpsKey())).isEqualTo("1");
    }

    @Test
    void shouldRejectWhenTokenQuotaIsExhausted() throws Exception {
        signIn("demo_a");
        MockHttpServletResponse last = null;
        for (int index = 0; index < tokenQuota; index++) {
            assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                    new MockHttpServletResponse(), handler(new BillableController(), "ask")))
                    .isTrue();
        }

        last = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(),
                last, handler(new BillableController(), "ask"));

        assertThat(allowed).isFalse();
        assertThat(last.getStatus()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(last.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.DEMO_QUOTA_EXHAUSTED.name());
        // The rejected attempt is rolled back so the counter stays at the quota.
        assertThat(counters.values.get(DemoConstant.QUOTA_TOKEN_PREFIX + TOKEN_HASH))
                .isEqualTo(String.valueOf(tokenQuota));
    }

    @Test
    void shouldRejectAndRollBackBothCountersWhenDailyLimitIsReached() throws Exception {
        dailyLimit = 1;
        rebuild();
        signIn("demo_a");

        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler(new BillableController(), "ask"))).isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(),
                response, handler(new BillableController(), "ask"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.DEMO_DAILY_LIMIT_REACHED.name());
        // No phantom credit on the token, no phantom charge on the day.
        assertThat(counters.values.get(DemoConstant.QUOTA_TOKEN_PREFIX + TOKEN_HASH)).isEqualTo("1");
        assertThat(counters.values.get(dailyOpsKey())).isEqualTo("1");
    }

    @Test
    void ownerTokenShouldNotSpendTheVisitorBudget() throws Exception {
        UserContextHolder.set(new RequestUserContext("system", "ADMIN", TOKEN_HASH));

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler(new BillableController(), "ask"));

        assertThat(allowed).isTrue();
        assertThat(counters.values).isEmpty();
    }

    @Test
    void killSwitchShouldRejectBillableCalls() throws Exception {
        signIn("demo_a");
        counters.values.put(DemoConstant.KILL_SWITCH_KEY, "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(),
                response, handler(new BillableController(), "ask"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo(ApiError.DEMO_DISABLED.name());
        assertThat(counters.values).doesNotContainKey(DemoConstant.QUOTA_TOKEN_PREFIX + TOKEN_HASH);
    }

    private void signIn(String userId) {
        UserContextHolder.set(new RequestUserContext(userId, "USER", TOKEN_HASH));
    }

    private String dailyOpsKey() {
        return DemoConstant.QUOTA_DAILY_OPS_PREFIX + java.time.LocalDate.now();
    }

    private HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(method));
    }

    /** Records increments the way a real StringRedisTemplate would. */
    private static final class Counters {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        synchronized long increment(String key, long delta) {
            long next = Long.parseLong(values.getOrDefault(key, "0")) + delta;
            values.put(key, String.valueOf(next));
            return next;
        }
    }

    private static final class CountingRedisTemplate extends StringRedisTemplate {

        private final Counters counters;

        CountingRedisTemplate(Counters counters) {
            this.counters = counters;
        }

        @Override
        @SuppressWarnings("unchecked")
        public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            return (org.springframework.data.redis.core.ValueOperations<String, String>)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{org.springframework.data.redis.core.ValueOperations.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "increment" -> counters.increment(
                                        (String) args[0], args.length > 1 ? (Long) args[1] : 1L);
                                case "decrement" -> counters.increment(
                                        (String) args[0], -(args.length > 1 ? (Long) args[1] : 1L));
                                case "get" -> counters.values.get((String) args[0]);
                                case "set" -> {
                                    counters.values.put((String) args[0], String.valueOf(args[1]));
                                    yield null;
                                }
                                case "toString" -> "CountingValueOperations";
                                default -> null;
                            });
        }

        @Override
        public Boolean expire(String key, java.time.Duration timeout) {
            return true;
        }

        @Override
        public Boolean delete(String key) {
            counters.values.remove(key);
            return true;
        }
    }

    static class BillableController {
        @DemoQuota
        public void ask() {
        }
    }

    static class UnannotatedController {
        public void list() {
        }
    }
}
