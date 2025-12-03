package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "liquidity_tx", uniqueConstraints = {
        @UniqueConstraint(name = "idx_liquidity_tx_chain_txhash_log", columnNames = {"chain_id", "tx_hash", "log_index"})
})
public class LiquidityTxEntity {

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

    @Column(name = "amount0")
    private BigDecimal amount0;

    @Column(name = "amount1")
    private BigDecimal amount1;

    @Column(name = "liquidity_amount")
    private BigDecimal liquidityAmount;

    @Column(name = "type")
    private String type; // MINT | BURN

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

    public BigDecimal getAmount0() {
        return amount0;
    }

    public void setAmount0(BigDecimal amount0) {
        this.amount0 = amount0;
    }

    public BigDecimal getAmount1() {
        return amount1;
    }

    public void setAmount1(BigDecimal amount1) {
        this.amount1 = amount1;
    }

    public BigDecimal getLiquidityAmount() {
        return liquidityAmount;
    }

    public void setLiquidityAmount(BigDecimal liquidityAmount) {
        this.liquidityAmount = liquidityAmount;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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
