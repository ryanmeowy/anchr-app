package com.smart.vision.core.integration.multimodal.service.cloud.aliyun.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Aliyun OCR configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.ocr")
public class AliyunOcrConfig {

    private String accessKeyId;
    private String accessKeySecret;
    private String endpoint;
}

