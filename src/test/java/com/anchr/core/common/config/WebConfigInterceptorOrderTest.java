package com.anchr.core.common.config;

import com.anchr.core.common.infrastructure.AccessTokenInterceptor;
import com.anchr.core.common.infrastructure.DemoQuotaInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The quota interceptor reads the caller's token hash from the request context, so it must run
 * after authentication. A swap here would make every billable call look anonymous and charge
 * nothing.
 */
class WebConfigInterceptorOrderTest {

    @Test
    void quotaInterceptorMustRunAfterAuthentication() {
        AccessTokenInterceptor authentication = mock(AccessTokenInterceptor.class);
        DemoQuotaInterceptor quota = mock(DemoQuotaInterceptor.class);

        RecordingRegistry registry = new RecordingRegistry();
        new WebConfig(authentication, quota).addInterceptors(registry);

        assertThat(registry.registered()).hasSize(2);
        assertThat(registry.registered().get(0)).isSameAs(authentication);
        assertThat(registry.registered().get(1)).isSameAs(quota);
    }

    /** Captures the interceptors in registration order. */
    private static final class RecordingRegistry extends InterceptorRegistry {

        private final List<Object> order = new java.util.ArrayList<>();

        @Override
        public InterceptorRegistration addInterceptor(
                org.springframework.web.servlet.HandlerInterceptor interceptor) {
            order.add(interceptor);
            return super.addInterceptor(interceptor);
        }

        List<Object> registered() {
            return List.copyOf(order);
        }
    }
}
