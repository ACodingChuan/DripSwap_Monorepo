package com.dripswap.bff.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporary NoOp Tracer configuration for T0 phase.
 * This provides a no-operation tracer to satisfy dependencies.
 */
@Configuration
public class NoOpTracerConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        return OpenTelemetry.noop();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("dripswap-noop");
    }
}
