package com.dripswap.bff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DripSwap BFF (Backend for Frontend) Application
 * Subgraph data sync and GraphQL API
 */
@SpringBootApplication
public class DripSwapBffApplication {

    private static final Logger logger = LoggerFactory.getLogger(DripSwapBffApplication.class);

    public static void main(String[] args) {
        logger.info("Starting DripSwap BFF Application...");
        SpringApplication.run(DripSwapBffApplication.class, args);
        logger.info("DripSwap BFF Application started successfully!");
    }
}
