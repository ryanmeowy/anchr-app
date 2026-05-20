package com.anchr.core.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    @NotBlank(message = "app.embedding.backend must not be blank")
    private String backend;

    @NotBlank(message = "app.embedding.model must not be blank")
    private String model;

    @NotNull(message = "app.embedding.dimension must not be null")
    @Positive(message = "app.embedding.dimension must be positive")
    private Integer dimension;

    private String preprocessVersion = "v1";

    private String imageInputMode = "auto";
}
