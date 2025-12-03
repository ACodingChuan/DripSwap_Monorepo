package com.dripswap.bff.modules.rest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Demo transaction entity mapped to demo_tx table.
 */
@Entity
@Table(name = "demo_tx")
public class DemoTx {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Constructors
    public DemoTx() {
    }

    public DemoTx(String chainId, String txHash, String status) {
        this.chainId = chainId;
        this.txHash = txHash;
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

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
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
        return "DemoTx{" +
                "id=" + id +
                ", chainId='" + chainId + '\'' +
                ", txHash='" + txHash + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
