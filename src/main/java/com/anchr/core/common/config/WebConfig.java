package com.anchr.core.common.config;

import com.anchr.core.common.infrastructure.AccessTokenInterceptor;
import com.anchr.core.common.infrastructure.DemoQuotaInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AccessTokenInterceptor accessTokenInterceptor;
    private final DemoQuotaInterceptor demoQuotaInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessTokenInterceptor)
                .addPathPatterns("/api/**");
        // Runs after authentication so the caller's token hash is already in the context.
        registry.addInterceptor(demoQuotaInterceptor)
                .addPathPatterns("/api/**");
    }
}
