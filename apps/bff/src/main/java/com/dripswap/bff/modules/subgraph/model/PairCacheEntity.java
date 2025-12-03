package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pair_cache", uniqueConstraints = {
        @UniqueConstraint(name = "idx_pair_cache_chain_address", columnNames = {"chain_id", "address"})
})
public class PairCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "token0_address")
    private String token0Address;

    @Column(name = "token1_address")
    private String token1Address;

    @Column(name = "reserve0")
    private BigDecimal reserve0;

    @Column(name = "reserve1")
    private BigDecimal reserve1;

    @Column(name = "liquidity")
    private BigDecimal liquidity;

    @Column(name = "volume_token0")
    private BigDecimal volumeToken0;

    @Column(name = "volume_token1")
    private BigDecimal volumeToken1;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

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

    public String getToken0Address() {
        return token0Address;
    }

    public void setToken0Address(String token0Address) {
        this.token0Address = token0Address;
    }

    public String getToken1Address() {
        return token1Address;
    }

    public void setToken1Address(String token1Address) {
        this.token1Address = token1Address;
    }

    public BigDecimal getReserve0() {
        return reserve0;
    }

    public void setReserve0(BigDecimal reserve0) {
        this.reserve0 = reserve0;
    }

    public BigDecimal getReserve1() {
        return reserve1;
    }

    public void setReserve1(BigDecimal reserve1) {
        this.reserve1 = reserve1;
    }

    public BigDecimal getLiquidity() {
        return liquidity;
    }

    public void setLiquidity(BigDecimal liquidity) {
        this.liquidity = liquidity;
    }

    public BigDecimal getVolumeToken0() {
        return volumeToken0;
    }

    public void setVolumeToken0(BigDecimal volumeToken0) {
        this.volumeToken0 = volumeToken0;
    }

    public BigDecimal getVolumeToken1() {
        return volumeToken1;
    }

    public void setVolumeToken1(BigDecimal volumeToken1) {
        this.volumeToken1 = volumeToken1;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
