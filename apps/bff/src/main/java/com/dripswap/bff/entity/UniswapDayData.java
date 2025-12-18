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
@Table(name = "uniswap_day_data")
public class UniswapDayData {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(nullable = false)
    private Integer date = 0;

    @Column(name = "daily_volume_eth", nullable = false)
    private BigDecimal dailyVolumeEth = BigDecimal.ZERO;

    @Column(name = "daily_volume_usd", nullable = false)
    private BigDecimal dailyVolumeUsd = BigDecimal.ZERO;

    @Column(name = "daily_volume_untracked", nullable = false)
    private BigDecimal dailyVolumeUntracked = BigDecimal.ZERO;

    @Column(name = "total_volume_eth", nullable = false)
    private BigDecimal totalVolumeEth = BigDecimal.ZERO;

    @Column(name = "total_volume_usd", nullable = false)
    private BigDecimal totalVolumeUsd = BigDecimal.ZERO;

    @Column(name = "total_liquidity_eth", nullable = false)
    private BigDecimal totalLiquidityEth = BigDecimal.ZERO;

    @Column(name = "total_liquidity_usd", nullable = false)
    private BigDecimal totalLiquidityUsd = BigDecimal.ZERO;

    @Column(name = "tx_count", nullable = false)
    private Long txCount = 0L;

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

