package com.smart.vision.core.integration.multimodal.service.cloud.volcengine.config;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.smart.vision.core.integration.constant.VolcengineConstant.ARK_API_KEY_ENV_NAME;

/**
 * Volcengine embedding sdk bean configuration.
 */
@Configuration
public class VolcengineEmbeddingBeanConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.capability-provider", name = "embedding", havingValue = "volcengine")
    public ArkService arkService() {
        return ArkService.builder()
                .dispatcher(new Dispatcher())
                .connectionPool(new ConnectionPool(5, 1, TimeUnit.SECONDS))
                .apiKey(resolveApiKey())
                .build();
    }

    private String resolveApiKey() {
        return Optional.ofNullable(System.getenv(ARK_API_KEY_ENV_NAME))
                .orElseThrow(() -> new BusinessException(ApiError.INVALID_API_KEY));
    }
}

