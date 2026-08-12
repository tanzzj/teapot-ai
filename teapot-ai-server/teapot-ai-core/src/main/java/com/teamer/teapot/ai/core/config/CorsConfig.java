package com.teamer.teapot.ai.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 白名单（SPEC §14.4）：仅放行配置来源；/agui/** 的 CORS 由 AG-UI starter 自管。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final TeapotAiProperties properties;

    public CorsConfig(TeapotAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.getCorsAllowedOrigins().toArray(new String[0]);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
