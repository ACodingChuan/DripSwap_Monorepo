package com.dripswap.bff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@IdClass(ChainEntityId.class)
@Table(name = "pairs")
public class Pair {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "token0_id", nullable = false)
    private String token0Id;

    @Column(name = "token1_id", nullable = false)
    private String token1Id;

    @Column(nullable = false)
    private BigDecimal reserve0 = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal reserve1 = BigDecimal.ZERO;

    @Column(name = "total_supply", nullable = false)
    private BigDecimal totalSupply = BigDecimal.ZERO;

    @Column(name = "reserve_eth", nullable = false)
    private BigDecimal reserveEth = BigDecimal.ZERO;

    @Column(name = "reserve_usd", nullable = false)
    private BigDecimal reserveUsd = BigDecimal.ZERO;

    @Column(name = "tracked_reserve_eth", nullable = false)
    private BigDecimal trackedReserveEth = BigDecimal.ZERO;

    @Column(name = "token0_price", nullable = false)
    private BigDecimal token0Price = BigDecimal.ZERO;

    @Column(name = "token1_price", nullable = false)
    private BigDecimal token1Price = BigDecimal.ZERO;

    @Column(name = "volume_token0", nullable = false)
    private BigDecimal volumeToken0 = BigDecimal.ZERO;

    @Column(name = "volume_token1", nullable = false)
    private BigDecimal volumeToken1 = BigDecimal.ZERO;

    @Column(name = "volume_usd", nullable = false)
    private BigDecimal volumeUsd = BigDecimal.ZERO;

    @Column(name = "untracked_volume_usd", nullable = false)
    private BigDecimal untrackedVolumeUsd = BigDecimal.ZERO;

    @Column(name = "tx_count", nullable = false)
    private Long txCount = 0L;

    @Column(name = "liquidity_provider_count", nullable = false)
    private Long liquidityProviderCount = 0L;

    @Column(name = "created_at_timestamp", nullable = false)
    private Long createdAtTimestamp = 0L;

    @Column(name = "created_at_block_number", nullable = false)
    private Long createdAtBlockNumber = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

