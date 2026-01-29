package com.dripswap.bff.repository;

import java.time.Instant;
import java.util.UUID;

public class CcipBridgeRecord {
    private UUID id;
    private String messageId;
    private String userAddress;
    private String tokenSymbol;
    private String tokenAddress;
    private long fromChainId;
    private long toChainId;
    private String sourceTxHash;
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public void setTokenSymbol(String tokenSymbol) {
        this.tokenSymbol = tokenSymbol;
    }

    public String getTokenAddress() {
        return tokenAddress;
    }

    public void setTokenAddress(String tokenAddress) {
        this.tokenAddress = tokenAddress;
    }

    public long getFromChainId() {
        return fromChainId;
    }

    public void setFromChainId(long fromChainId) {
        this.fromChainId = fromChainId;
    }

    public long getToChainId() {
        return toChainId;
    }

    public void setToChainId(long toChainId) {
        this.toChainId = toChainId;
    }

    public String getSourceTxHash() {
        return sourceTxHash;
    }

    public void setSourceTxHash(String sourceTxHash) {
        this.sourceTxHash = sourceTxHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

