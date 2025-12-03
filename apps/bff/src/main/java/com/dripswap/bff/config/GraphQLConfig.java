package com.dripswap.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.scalars.ExtendedScalars;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.web.client.RestTemplate;

/**
 * GraphQL configuration for Spring Boot.
 * Registers beans for GraphQL resolvers and dependencies.
 */
@Configuration
@EnableConfigurationProperties
public class GraphQLConfig {

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(ExtendedScalars.GraphQLBigDecimal);
    }

    /**
     * RestTemplate bean for HTTP requests.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * ObjectMapper bean for JSON processing.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
