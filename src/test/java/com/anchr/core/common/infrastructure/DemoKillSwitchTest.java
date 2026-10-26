package com.anchr.core.common.infrastructure;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;

class DemoKillSwitchTest {

    @Test
    void shouldBeOffWhenTheDemoIsDisabledRegardlessOfRedis() {
        Map<String, String> values = new ConcurrentHashMap<>();
        values.put(DemoConstant.KILL_SWITCH_KEY, "1");
        DemoKillSwitch killSwitch = new DemoKillSwitch(
                new FakeRedis(values), DemoProperties.defaults());

        assertThat(killSwitch.isOn()).isFalse();
    }

    @Test
    void shouldReflectTheStoredFlag() {
        Map<String, String> values = new ConcurrentHashMap<>();
        DemoProperties enabled = new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", false);
        DemoKillSwitch killSwitch = new DemoKillSwitch(new FakeRedis(values), enabled);

        assertThat(killSwitch.isOn()).isFalse();
        killSwitch.set(true);
        assertThat(killSwitch.isOn()).isTrue();
        killSwitch.set(false);
        assertThat(killSwitch.isOn()).isFalse();
    }

    @Test
    void startClosedShouldStopTrafficAtBoot() {
        Map<String, String> values = new ConcurrentHashMap<>();
        DemoProperties closed = new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", true);
        DemoKillSwitch killSwitch = new DemoKillSwitch(new FakeRedis(values), closed);

        killSwitch.applyStartState();

        assertThat(killSwitch.isOn()).isTrue();
        assertThat(values.get(DemoConstant.KILL_SWITCH_KEY)).isEqualTo("1");
    }

    @Test
    void restartShouldKeepASwitchSomebodyAlreadyClosed() {
        Map<String, String> values = new ConcurrentHashMap<>();
        values.put(DemoConstant.KILL_SWITCH_KEY, "1");
        DemoProperties open = new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", false);
        DemoKillSwitch killSwitch = new DemoKillSwitch(new FakeRedis(values), open);

        killSwitch.applyStartState();

        assertThat(killSwitch.isOn()).isTrue();
    }

    @Test
    void startStateShouldBeIgnoredWhileTheDemoIsDisabled() {
        Map<String, String> values = new ConcurrentHashMap<>();
        DemoKillSwitch killSwitch = new DemoKillSwitch(
                new FakeRedis(values), DemoProperties.defaults());

        killSwitch.applyStartState();

        assertThat(killSwitch.isOn()).isFalse();
        assertThat(values).isEmpty();
    }

    @Test
    void shouldFailOpenWhenRedisIsUnreachable() {
        DemoProperties enabled = new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", false);
        DemoKillSwitch killSwitch = new DemoKillSwitch(new BrokenRedis(), enabled);

        // Quota counters live in the same Redis, so refusing traffic here would be a second
        // outage on top of the first.
        assertThat(killSwitch.isOn()).isFalse();
    }

    private static final class FakeRedis extends StringRedisTemplate {

        private final Map<String, String> values;

        FakeRedis(Map<String, String> values) {
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
                        case "toString" -> "FakeValueOperations";
                        default -> null;
                    });
        }

        @Override
        public Boolean delete(String key) {
            values.remove(key);
            return true;
        }
    }

    private static final class BrokenRedis extends StringRedisTemplate {

        @Override
        public ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("redis down");
        }
    }
}
