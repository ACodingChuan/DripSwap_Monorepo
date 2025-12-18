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
@Table(name = "pair_day_data")
public class PairDayData {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(nullable = false)
    private Integer date = 0;

    @Column(name = "pair_address", nullable = false)
    private String pairAddress;

    @Column(name = "token0_id", nullable = false)
    private String token0Id;

    @Column(name = "token1_id", nullable = false)
    private String token1Id;

    @Column(nullable = false)
    private BigDecimal reserve0 = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal reserve1 = BigDecimal.ZERO;

    @Column(name = "total_supply")
    private BigDecimal totalSupply;

    @Column(name = "reserve_usd", nullable = false)
    private BigDecimal reserveUsd = BigDecimal.ZERO;

    @Column(name = "daily_volume_token0", nullable = false)
    private BigDecimal dailyVolumeToken0 = BigDecimal.ZERO;

    @Column(name = "daily_volume_token1", nullable = false)
    private BigDecimal dailyVolumeToken1 = BigDecimal.ZERO;

    @Column(name = "daily_volume_usd", nullable = false)
    private BigDecimal dailyVolumeUsd = BigDecimal.ZERO;

    @Column(name = "daily_txns", nullable = false)
    private Long dailyTxns = 0L;

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

