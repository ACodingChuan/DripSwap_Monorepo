package com.dripswap.bff.modules.chains.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket RPC client for blockchain subscription
 * Manages WebSocket connection with reconnect support
 */
public class WsRpcClient {

    private static final Logger logger = LoggerFactory.getLogger(WsRpcClient.class);

    private final String chainId;
    private final String wsUrl;
    private final int maxRetries;

    private WebSocketService webSocketService;
    private Web3j web3j;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public WsRpcClient(String chainId, String wsUrl, int maxRetries) {
        this.chainId = chainId;
        this.wsUrl = wsUrl;
        this.maxRetries = maxRetries;
    }

    /**
     * Connect to WebSocket RPC endpoint
     */
    public synchronized void connect() throws Exception {
        if (connected.get()) {
            logger.debug("Already connected to chain {}", chainId);
            return;
        }

        try {
            this.webSocketService = new WebSocketService(wsUrl, true);
            webSocketService.connect();
            this.web3j = Web3j.build(webSocketService);
            connected.set(true);
            logger.info("Connected to chain {} via WebSocket: {}", chainId, wsUrl);
        } catch (Exception e) {
            logger.error("Failed to connect to chain {} via WebSocket: {}", chainId, wsUrl, e);
            throw e;
        }
    }

    /**
     * Check if connected
     */
    public boolean isAlive() {
        if (!connected.get()) {
            return false;
        }
        try {
            return webSocketService != null && web3j != null;
        } catch (Exception e) {
            logger.debug("Health check failed for chain {}", chainId);
            return false;
        }
    }

    /**
     * Reconnect with retry logic
     */
    public synchronized void reconnect() throws Exception {
        logger.info("Attempting to reconnect chain {}", chainId);
        close();
        connect();
    }

    /**
     * Close connection
     */
    public synchronized void close() {
        try {
            if (webSocketService != null) {
                webSocketService.close();
            }
            if (web3j != null) {
                web3j.shutdown();
            }
            connected.set(false);
            logger.info("Closed WebSocket connection for chain {}", chainId);
        } catch (Exception e) {
            logger.error("Error closing connection for chain {}", chainId, e);
        }
    }

    /**
     * Get Web3j instance
     */
    public Web3j getWeb3j() {
        return web3j;
    }

    /**
     * Get chain ID
     */
    public String getChainId() {
        return chainId;
    }

    /**
     * Get max retries
     */
    public int getMaxRetries() {
        return maxRetries;
    }
}
