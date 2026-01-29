package com.dripswap.bff.repository;

import java.util.UUID;

public class StoredRequest {
    private UUID requestId;
    private String status;
    private String txHash;

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }
}
