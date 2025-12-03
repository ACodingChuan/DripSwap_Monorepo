package com.dripswap.bff.modules.tx.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Transaction record entity mapped to tx_records table.
 * Represents a parsed and decoded blockchain transaction event.
 */
@Entity
@Table(name = "tx_records")
public class TxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "block_number", nullable = false)
    private Long blockNumber;

    @Column(name = "tx_hash", nullable = false)
    private String txHash;

    @Column(name = "event_sig")
    private String eventSig;

    @Column(name = "decoded_name")
    private String decodedName;

    @Column(name = "decoded_data", columnDefinition = "TEXT")
    private String decodedData;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Constructors
    public TxRecord() {
    }

    public TxRecord(String chainId, Long blockNumber, String txHash, String eventSig, 
                    String decodedName, String decodedData, String status) {
        this.chainId = chainId;
        this.blockNumber = blockNumber;
        this.txHash = txHash;
        this.eventSig = eventSig;
        this.decodedName = decodedName;
        this.decodedData = decodedData;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
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

    public Long getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(Long blockNumber) {
        this.blockNumber = blockNumber;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public String getEventSig() {
        return eventSig;
    }

    public void setEventSig(String eventSig) {
        this.eventSig = eventSig;
    }

    public String getDecodedName() {
        return decodedName;
    }

    public void setDecodedName(String decodedName) {
        this.decodedName = decodedName;
    }

    public String getDecodedData() {
        return decodedData;
    }

    public void setDecodedData(String decodedData) {
        this.decodedData = decodedData;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "TxRecord{" +
                "id=" + id +
                ", chainId='" + chainId + '\'' +
                ", blockNumber=" + blockNumber +
                ", txHash='" + txHash + '\'' +
                ", eventSig='" + eventSig + '\'' +
                ", decodedName='" + decodedName + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
