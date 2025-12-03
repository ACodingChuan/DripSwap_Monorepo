package com.dripswap.bff.modules.chains.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Raw blockchain event entity mapped to raw_events table
 */
@Entity
@Table(name = "raw_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "log_index")
    private Integer logIndex;

    @Column(name = "event_sig")
    private String eventSig;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Generate unique key for idempotency check
     */
    public String getUniqueKey() {
        return String.format("%s_%d_%s_%d", chainId, blockNumber, txHash, logIndex);
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getChainId() {
        return chainId;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public String getTxHash() {
        return txHash;
    }

    public Integer getLogIndex() {
        return logIndex;
    }

    public String getEventSig() {
        return eventSig;
    }

    public String getRawData() {
        return rawData;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public void setBlockNumber(Long blockNumber) {
        this.blockNumber = blockNumber;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public void setLogIndex(Integer logIndex) {
        this.logIndex = logIndex;
    }

    public void setEventSig(String eventSig) {
        this.eventSig = eventSig;
    }

    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
