package com.dripswap.bff.modules.chains.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chain cursor entity for tracking the latest processed block
 * Mapped to chain_cursor table
 */
@Entity
@Table(name = "chain_cursor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "last_block_number")
    private Long lastBlockNumber;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    @PrePersist
    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getChainId() {
        return chainId;
    }

    public Long getLastBlockNumber() {
        return lastBlockNumber;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public void setLastBlockNumber(Long lastBlockNumber) {
        this.lastBlockNumber = lastBlockNumber;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
