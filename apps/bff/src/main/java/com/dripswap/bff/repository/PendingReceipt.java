package com.dripswap.bff.repository;

import java.time.Instant;
import java.util.UUID;

public class PendingReceipt {
    private UUID requestId;
    private long chainId;
    private String txHash;
    private Instant checkAt;
    private Instant createdAt;

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public long getChainId() {
        return chainId;
    }

    public void setChainId(long chainId) {
        this.chainId = chainId;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public Instant getCheckAt() {
        return checkAt;
    }

    public void setCheckAt(Instant checkAt) {
        this.checkAt = checkAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
