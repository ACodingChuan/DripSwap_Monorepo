package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bridge_leg", uniqueConstraints = {
        @UniqueConstraint(name = "idx_bridge_leg_chain_tx_log", columnNames = {"chain_id", "tx_hash", "log_index"})
})
public class BridgeLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "log_index")
    private Integer logIndex;

    @Column(name = "pool")
    private String pool;

    @Column(name = "token")
    private String token;

    @Column(name = "remote_chain_selector")
    private Long remoteChainSelector;

    @Column(name = "sender")
    private String sender;

    @Column(name = "recipient")
    private String recipient;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "leg_type")
    private String legType; // LOCKED_OR_BURNED / RELEASED_OR_MINTED

    @Column(name = "correlation_type")
    private String correlationType; // Exact | Heuristic | None

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "block_timestamp")
    private Long blockTimestamp;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public Integer getLogIndex() {
        return logIndex;
    }

    public void setLogIndex(Integer logIndex) {
        this.logIndex = logIndex;
    }

    public String getPool() {
        return pool;
    }

    public void setPool(String pool) {
        this.pool = pool;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getRemoteChainSelector() {
        return remoteChainSelector;
    }

    public void setRemoteChainSelector(Long remoteChainSelector) {
        this.remoteChainSelector = remoteChainSelector;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getLegType() {
        return legType;
    }

    public void setLegType(String legType) {
        this.legType = legType;
    }

    public String getCorrelationType() {
        return correlationType;
    }

    public void setCorrelationType(String correlationType) {
        this.correlationType = correlationType;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(Long blockNumber) {
        this.blockNumber = blockNumber;
    }

    public Long getBlockTimestamp() {
        return blockTimestamp;
    }

    public void setBlockTimestamp(Long blockTimestamp) {
        this.blockTimestamp = blockTimestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
