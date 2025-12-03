package com.dripswap.bff;

import com.dripswap.bff.modules.chains.events.ChainEventListener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DripSwap BFF (Backend for Frontend) Application
 * Multi-chain event aggregation and data layer
 */
@SpringBootApplication
@EnableScheduling
public class DripSwapBffApplication {

    private static final Logger logger = LoggerFactory.getLogger(DripSwapBffApplication.class);
    private final ChainEventListener chainEventListener;

    public DripSwapBffApplication(ChainEventListener chainEventListener) {
        this.chainEventListener = chainEventListener;
    }

    public static void main(String[] args) {
        SpringApplication.run(DripSwapBffApplication.class, args);
    }

    /**
     * Start chain event listeners after application initialization
     */
    @PostConstruct
    public void initializeChainListeners() {
        try {
            logger.info("Initializing chain event listeners");
            // Note: ChainEventListener.startListening() is triggered automatically via @EventListener(ApplicationReadyEvent.class)
            logger.info("Chain event listeners will start when application is ready");
        } catch (Exception e) {
            logger.error("Failed to initialize chain event listeners", e);
            // Non-fatal: application continues to run even if listeners fail
        }
    }
}
