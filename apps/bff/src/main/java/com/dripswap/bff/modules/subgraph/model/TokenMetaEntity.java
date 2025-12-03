package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "token_meta", uniqueConstraints = {
        @UniqueConstraint(name = "idx_token_meta_chain_address", columnNames = {"chain_id", "address"})
})
public class TokenMetaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "name")
    private String name;

    @Column(name = "decimals")
    private Integer decimals;

    @Column(name = "total_supply")
    private BigDecimal totalSupply;

    @Column(name = "synced_at")
    private Instant syncedAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDecimals() {
        return decimals;
    }

    public void setDecimals(Integer decimals) {
        this.decimals = decimals;
    }

    public BigDecimal getTotalSupply() {
        return totalSupply;
    }

    public void setTotalSupply(BigDecimal totalSupply) {
        this.totalSupply = totalSupply;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Instant syncedAt) {
        this.syncedAt = syncedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
