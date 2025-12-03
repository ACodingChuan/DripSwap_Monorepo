package com.dripswap.bff.modules.chains.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a single blockchain network
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainConfig {

    /**
     * Unique identifier for the chain (e.g., "sepolia", "scroll-sepolia")
     */
    private String id;

    /**
     * Human-readable chain name
     */
    private String name;

    /**
     * EVM chain ID (e.g., 11155111 for Sepolia)
     */
    private Long chainId;

    /**
     * Whether this chain is enabled for listening
     */
    private boolean enabled;

    /**
     * HTTP RPC endpoint
     */
    private String rpcHttp;

    /**
     * WebSocket RPC endpoint
     */
    private String rpcWs;

    /**
     * Validate the configuration is complete
     */
    public void validate() {
        if (this.id == null || this.id.isEmpty()) {
            throw new IllegalArgumentException("Chain id cannot be empty");
        }
        if (this.chainId == null || this.chainId <= 0) {
            throw new IllegalArgumentException("Chain chainId must be positive");
        }
        if (this.enabled && (this.rpcWs == null || this.rpcWs.isEmpty())) {
            throw new IllegalArgumentException("Chain rpcWs cannot be empty for enabled chains");
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getChainId() {
        return chainId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getRpcHttp() {
        return rpcHttp;
    }

    public String getRpcWs() {
        return rpcWs;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setChainId(Long chainId) {
        this.chainId = chainId;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setRpcHttp(String rpcHttp) {
        this.rpcHttp = rpcHttp;
    }

    public void setRpcWs(String rpcWs) {
        this.rpcWs = rpcWs;
    }
}
