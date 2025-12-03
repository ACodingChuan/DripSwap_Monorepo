package com.dripswap.bff.modules.chains.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * HTTP RPC client for blockchain interactions
 * Used for historical queries and non-subscription operations
 */
public class HttpRpcClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpRpcClient.class);

    private final String chainId;
    private final String httpUrl;
    private Web3j web3j;

    public HttpRpcClient(String chainId, String httpUrl) {
        this.chainId = chainId;
        this.httpUrl = httpUrl;
    }

    /**
     * Initialize HTTP client
     */
    public void init() throws Exception {
        try {
            this.web3j = Web3j.build(new HttpService(httpUrl));
            logger.info("Initialized HTTP client for chain {}: {}", chainId, httpUrl);
        } catch (Exception e) {
            logger.error("Failed to initialize HTTP client for chain {}", chainId, e);
            throw e;
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
     * Close HTTP client
     */
    public void close() {
        if (web3j != null) {
            web3j.shutdown();
            logger.info("Closed HTTP client for chain {}", chainId);
        }
    }
}
