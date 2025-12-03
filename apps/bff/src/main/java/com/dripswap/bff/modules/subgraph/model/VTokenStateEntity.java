package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "vtoken_state", uniqueConstraints = {
        @UniqueConstraint(name = "idx_vtoken_state_chain_address", columnNames = {"chain_id", "address"})
})
public class VTokenStateEntity {

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

    @Column(name = "total_minted")
    private BigDecimal totalMinted;

    @Column(name = "total_burned")
    private BigDecimal totalBurned;

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

    public BigDecimal getTotalMinted() {
        return totalMinted;
    }

    public void setTotalMinted(BigDecimal totalMinted) {
        this.totalMinted = totalMinted;
    }

    public BigDecimal getTotalBurned() {
        return totalBurned;
    }

    public void setTotalBurned(BigDecimal totalBurned) {
        this.totalBurned = totalBurned;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
