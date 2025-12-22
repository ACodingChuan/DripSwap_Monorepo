package com.dripswap.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for browser clients (Vite dev server, etc).
 *
 * <p>Without this, browsers will block GraphQL requests due to failed preflight (OPTIONS) requests.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Comma-delimited origin patterns.
     *
     * <p>Default allows any localhost port so dev works even if Vite port changes.
     */
    @Value("${BFF_CORS_ALLOWED_ORIGINS:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = StringUtils.commaDelimitedListToStringArray(allowedOriginPatterns);
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

