package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bridge_tx", uniqueConstraints = {
        @UniqueConstraint(name = "idx_bridge_tx_chain_message", columnNames = {"chain_id", "message_id"})
})
public class BridgeTxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "sender")
    private String sender;

    @Column(name = "receiver")
    private String receiver;

    @Column(name = "token")
    private String token;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "pool")
    private String pool;

    @Column(name = "pay_in_link")
    private Boolean payInLink;

    @Column(name = "ccip_fee")
    private BigDecimal ccipFee;

    @Column(name = "service_fee_paid")
    private BigDecimal serviceFeePaid;

    @Column(name = "status")
    private String status;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "block_timestamp")
    private Long blockTimestamp;

    @Column(name = "tx_hash")
    private String txHash;

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

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getPool() {
        return pool;
    }

    public void setPool(String pool) {
        this.pool = pool;
    }

    public Boolean getPayInLink() {
        return payInLink;
    }

    public void setPayInLink(Boolean payInLink) {
        this.payInLink = payInLink;
    }

    public BigDecimal getCcipFee() {
        return ccipFee;
    }

    public void setCcipFee(BigDecimal ccipFee) {
        this.ccipFee = ccipFee;
    }

    public BigDecimal getServiceFeePaid() {
        return serviceFeePaid;
    }

    public void setServiceFeePaid(BigDecimal serviceFeePaid) {
        this.serviceFeePaid = serviceFeePaid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
