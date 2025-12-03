package com.dripswap.bff.modules.rest.dto;

/**
 * Request DTO for demo transaction submission.
 */
public class DemoTxRequest {

    private String txHash;
    private String chainId;
    private String payload;

    // Constructors
    public DemoTxRequest() {
    }

    public DemoTxRequest(String txHash, String chainId, String payload) {
        this.txHash = txHash;
        this.chainId = chainId;
        this.payload = payload;
    }

    // Getters and Setters
    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "DemoTxRequest{" +
                "txHash='" + txHash + '\'' +
                ", chainId='" + chainId + '\'' +
                ", payload='" + payload + '\'' +
                '}';
    }
}
