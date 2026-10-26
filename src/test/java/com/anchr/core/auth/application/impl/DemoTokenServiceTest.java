package com.anchr.core.auth.application.impl;

import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.auth.application.impl.DemoTokenService.DemoTokenResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoTokenServiceTest {

    private Map<String, String> values;
    private DemoTokenService service;

    @BeforeEach
    void setUp() {
        values = new ConcurrentHashMap<>();
        DemoProperties properties = new DemoProperties(true, 5, 8, 80, 2, 10, 1, false, "", "", false);
        service = new DemoTokenService(new FakeRedisTemplate(values),
                new ObjectMapper(), properties);
    }

    @Test
    void shouldIssueUserTokenCarryingAnIndependentVisitorIdentity() {
        DemoTokenResult result = service.issue(null);

        assertThat(result.created()).isTrue();
        assertThat(result.browserCookie()).isNotBlank();
        assertThat(result.issue().userId()).startsWith("demo_");
        assertThat(result.issue().token()).isNotBlank();

        String stored = values.get(DemoConstant.TOKEN_CACHE_PREFIX + result.issue().token());
        assertThat(stored).contains("\"role\":\"USER\"").contains("\"demo\":true");
    }

    @Test
    void shouldReturnTheSameTokenForTheSameBrowserCookie() {
        DemoTokenResult first = service.issue("browser-1");
        DemoTokenResult second = service.issue("browser-1");

        assertThat(second.created()).isFalse();
        assertThat(second.issue().token()).isEqualTo(first.issue().token());
        assertThat(second.issue().userId()).isEqualTo(first.issue().userId());
        // Idempotent reuse must not consume another daily slot.
        assertThat(values.get(DemoConstant.QUOTA_DAILY_ISSUED_PREFIX + java.time.LocalDate.now()))
                .isEqualTo("1");
    }

    @Test
    void shouldRefuseWhenDailyIssueLimitIsReached() {
        service.issue("browser-1");
        service.issue("browser-2");

        assertThatThrownBy(() -> service.issue("browser-3"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getError())
                .isEqualTo(ApiError.DEMO_DAILY_LIMIT_REACHED);
        // The rejected attempt is rolled back.
        assertThat(values.get(DemoConstant.QUOTA_DAILY_ISSUED_PREFIX + java.time.LocalDate.now()))
                .isEqualTo("2");
    }

    @Test
    void shouldRefuseDuringCooldownWithoutAnIssuedToken() {
        service.issue("browser-1");
        values.remove(DemoConstant.ISSUE_TOKEN_PREFIX + "browser-1");

        assertThatThrownBy(() -> service.issue("browser-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getError())
                .isEqualTo(ApiError.DEMO_ISSUE_TOO_FREQUENT);
    }

    /** Minimal in-memory stand-in; only the operations the service uses. */
    private static final class FakeRedisTemplate extends StringRedisTemplate {

        private final Map<String, String> values;

        FakeRedisTemplate(Map<String, String> values) {
            this.values = values;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> values.get((String) args[0]);
                        case "set" -> {
                            values.put((String) args[0], String.valueOf(args[1]));
                            yield null;
                        }
                        case "increment" -> {
                            long next = Long.parseLong(
                                    values.getOrDefault((String) args[0], "0")) + 1;
                            values.put((String) args[0], String.valueOf(next));
                            yield next;
                        }
                        case "decrement" -> {
                            long next = Long.parseLong(
                                    values.getOrDefault((String) args[0], "0")) - 1;
                            values.put((String) args[0], String.valueOf(next));
                            yield next;
                        }
                        case "toString" -> "FakeValueOperations";
                        default -> null;
                    });
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            return true;
        }

        @Override
        public Boolean delete(String key) {
            values.remove(key);
            return true;
        }
    }
}
