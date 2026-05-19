package com.smart.vision.core.integration.multimodal.service.cloud.aliyun.config;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.teaopenapi.models.Config;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aliyun OCR sdk bean configuration.
 */
@Configuration
@RequiredArgsConstructor
public class AliyunOcrBeanConfig {

    private final AliyunOcrConfig aliyunOcrConfig;

    @Bean
    @Deprecated
    @ConditionalOnProperty(prefix = "app.capability-provider", name = "ocr", havingValue = "aliyun")
    public Client ocrClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(aliyunOcrConfig.getAccessKeyId())
                .setAccessKeySecret(aliyunOcrConfig.getAccessKeySecret())
                .setEndpoint(aliyunOcrConfig.getEndpoint());
        return new Client(config);
    }
}

