package com.dripswap.bff.modules.chains.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for all blockchain chain configurations
 * Loads from application.yaml dripswap.chains property
 */
@Component
@ConfigurationProperties(prefix = "dripswap")
public class ChainRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChainRegistry.class);
    private List<ChainConfig> chains = new ArrayList<>();

    public List<ChainConfig> getChains() {
        return chains;
    }

    public void setChains(List<ChainConfig> chains) {
        this.chains = chains;
        // Validate all chains on set
        chains.forEach(ChainConfig::validate);
    }

    /**
     * Get all chain configurations
     */
    public List<ChainConfig> getAllChains() {
        return new ArrayList<>(chains);
    }

    /**
     * Get all enabled chain configurations
     */
    public List<ChainConfig> getEnabledChains() {
        return chains.stream()
                .filter(ChainConfig::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * Get chain configuration by chain id (e.g., "sepolia")
     */
    public Optional<ChainConfig> getById(String id) {
        return chains.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }

    /**
     * Get chain configuration by EVM chain ID (e.g., 11155111)
     */
    public Optional<ChainConfig> getByChainId(Long chainId) {
        return chains.stream()
                .filter(c -> c.getChainId().equals(chainId))
                .findFirst();
    }

    /**
     * Get chain configuration by name
     */
    public Optional<ChainConfig> getByName(String name) {
        return chains.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst();
    }

    /**
     * Check if chain is enabled
     */
    public boolean isEnabled(String chainId) {
        return getById(chainId)
                .map(ChainConfig::isEnabled)
                .orElse(false);
    }

    /**
     * Log registry information
     */
    public void logRegistry() {
        logger.info("=== Chain Registry ===");
        chains.forEach(chain -> {
            logger.info("Chain: {} (chainId: {}), enabled: {}, RPC-WS: {}",
                    chain.getId(), chain.getChainId(), chain.isEnabled(), chain.getRpcWs());
        });
    }
}
