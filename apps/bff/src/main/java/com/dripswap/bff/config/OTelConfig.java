package com.dripswap.bff.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.api.common.Attributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry configuration.
 * Initializes OTLP exporter and provides Tracer bean.
 */
//@Configuration  // Temporarily disabled for T0
public class OTelConfig {

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Value("${spring.application.name:dripswap-bff}")
    private String serviceName;

    /**
     * Create OpenTelemetry SDK with OTLP exporter.
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        // Create OTLP exporter
        OtlpGrpcSpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(java.time.Duration.ofSeconds(5))
                .build();

        // Create resource with service name
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.builder()
                        .put("service.name", serviceName)
                        .build()));

        // Create tracer provider with batch span processor
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
                .setResource(resource)
                .build();

        // Create and return OpenTelemetry SDK
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        // Hook shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                tracerProvider.close();
                otlpExporter.close();
            } catch (Exception e) {
                // Silent close
            }
        }));

        return openTelemetry;
    }

    /**
     * Provide Tracer bean for injection.
     */
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("dripswap", "1.0.0");
    }
}
