package com.dripswap.bff.modules.chains.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages WebSocket connections to multiple blockchain RPC endpoints.
 * Loads WS RPC URLs from application.yaml dripswap.chains configuration.
 */
@Component
@ConfigurationProperties(prefix = "dripswap")
public class WsConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(WsConnectionManager.class);

    private List<ChainProperties> chains;
    private Map<String, Web3j> web3jInstances = new HashMap<>();
    private Map<String, WebSocketService> wsServices = new HashMap<>();

    public void setChains(List<ChainProperties> chains) {
        this.chains = chains;
    }

    /**
     * Initialize WebSocket connections for all enabled chains.
     * Called during application startup.
     */
    public void initializeConnections() {
        if (chains == null || chains.isEmpty()) {
            logger.warn("No chains configured in dripswap.chains");
            return;
        }

        for (ChainProperties chain : chains) {
            if (!chain.isEnabled()) {
                logger.debug("Chain {} is disabled, skipping", chain.getId());
                continue;
            }

            try {
                connectToChain(chain);
            } catch (Exception e) {
                logger.error("Failed to connect to chain {}: {}", chain.getId(), e.getMessage(), e);
            }
        }

        logger.info("Initialized {} WebSocket connection(s)", web3jInstances.size());
    }

    /**
     * Connect to a specific blockchain via WebSocket.
     */
    private void connectToChain(ChainProperties chain) throws Exception {
        String chainId = chain.getId();
        String wsUrl = chain.getRpc().getWs();

        if (wsUrl == null || wsUrl.isEmpty()) {
            logger.warn("No WebSocket RPC URL configured for chain {}", chainId);
            return;
        }

        logger.info("Connecting to chain {} via WebSocket: {}", chainId, wsUrl);

        try {
            WebSocketService wsService = new WebSocketService(wsUrl, true);
            wsService.connect();
            Web3j web3j = Web3j.build(wsService);

            web3jInstances.put(chainId, web3j);
            wsServices.put(chainId, wsService);

            logger.info("Successfully connected to chain {}", chainId);
        } catch (Exception e) {
            logger.error("Failed to create WebSocket service for chain {}", chainId, e);
            throw e;
        }
    }

    /**
     * Get Web3j instance for a specific chain.
     */
    public Optional<Web3j> getWeb3j(String chainId) {
        return Optional.ofNullable(web3jInstances.get(chainId));
    }

    /**
     * Get all Web3j instances.
     */
    public Map<String, Web3j> getAllWeb3jInstances() {
        return new HashMap<>(web3jInstances);
    }

    /**
     * Reconnect a chain (useful for recovery from disconnection).
     */
    public void reconnectChain(String chainId) {
        Optional<ChainProperties> chainOpt = chains.stream()
                .filter(c -> c.getId().equals(chainId))
                .findFirst();

        if (chainOpt.isEmpty()) {
            logger.warn("Chain {} not found", chainId);
            return;
        }

        try {
            closeChain(chainId);
            connectToChain(chainOpt.get());
            logger.info("Reconnected to chain {}", chainId);
        } catch (Exception e) {
            logger.error("Failed to reconnect to chain {}: {}", chainId, e.getMessage(), e);
        }
    }

    /**
     * Close WebSocket connection for a specific chain.
     */
    public void closeChain(String chainId) {
        WebSocketService wsService = wsServices.remove(chainId);
        web3jInstances.remove(chainId);

        if (wsService != null) {
            try {
                wsService.close();
                logger.info("Closed WebSocket connection for chain {}", chainId);
            } catch (Exception e) {
                logger.error("Error closing WebSocket for chain {}: {}", chainId, e.getMessage());
            }
        }
    }

    /**
     * Close all WebSocket connections.
     */
    public void closeAll() {
        for (String chainId : new HashMap<>(wsServices).keySet()) {
            closeChain(chainId);
        }
        logger.info("Closed all WebSocket connections");
    }

    /**
     * Chain properties inner class for YAML mapping.
     */
    public static class ChainProperties {
        private String id;
        private String name;
        private long chainId;
        private boolean enabled;
        private RpcProperties rpc;

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getChainId() {
            return chainId;
        }

        public void setChainId(long chainId) {
            this.chainId = chainId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RpcProperties getRpc() {
            return rpc;
        }

        public void setRpc(RpcProperties rpc) {
            this.rpc = rpc;
        }
    }

    /**
     * RPC properties inner class for YAML mapping.
     */
    public static class RpcProperties {
        private String http;
        private String ws;

        public String getHttp() {
            return http;
        }

        public void setHttp(String http) {
            this.http = http;
        }

        public String getWs() {
            return ws;
        }

        public void setWs(String ws) {
            this.ws = ws;
        }
    }
}
