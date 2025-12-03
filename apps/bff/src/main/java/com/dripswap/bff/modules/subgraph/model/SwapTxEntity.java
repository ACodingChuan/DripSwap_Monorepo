package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "swap_tx", uniqueConstraints = {
        @UniqueConstraint(name = "idx_swap_tx_chain_txhash_log", columnNames = {"chain_id", "tx_hash", "log_index"})
})
public class SwapTxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "pair_address")
    private String pairAddress;

    @Column(name = "sender")
    private String sender;

    @Column(name = "amount0_in")
    private BigDecimal amount0In;

    @Column(name = "amount1_in")
    private BigDecimal amount1In;

    @Column(name = "amount0_out")
    private BigDecimal amount0Out;

    @Column(name = "amount1_out")
    private BigDecimal amount1Out;

    @Column(name = "to_address")
    private String toAddress;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "block_timestamp")
    private Long blockTimestamp;

    @Column(name = "log_index")
    private Integer logIndex;

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

    public String getPairAddress() {
        return pairAddress;
    }

    public void setPairAddress(String pairAddress) {
        this.pairAddress = pairAddress;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public BigDecimal getAmount0In() {
        return amount0In;
    }

    public void setAmount0In(BigDecimal amount0In) {
        this.amount0In = amount0In;
    }

    public BigDecimal getAmount1In() {
        return amount1In;
    }

    public void setAmount1In(BigDecimal amount1In) {
        this.amount1In = amount1In;
    }

    public BigDecimal getAmount0Out() {
        return amount0Out;
    }

    public void setAmount0Out(BigDecimal amount0Out) {
        this.amount0Out = amount0Out;
    }

    public BigDecimal getAmount1Out() {
        return amount1Out;
    }

    public void setAmount1Out(BigDecimal amount1Out) {
        this.amount1Out = amount1Out;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
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

    public Integer getLogIndex() {
        return logIndex;
    }

    public void setLogIndex(Integer logIndex) {
        this.logIndex = logIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
