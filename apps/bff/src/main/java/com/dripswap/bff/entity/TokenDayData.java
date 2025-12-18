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
@Table(name = "token_day_data")
public class TokenDayData {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(nullable = false)
    private Integer date = 0;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "daily_volume_token", nullable = false)
    private BigDecimal dailyVolumeToken = BigDecimal.ZERO;

    @Column(name = "daily_volume_eth", nullable = false)
    private BigDecimal dailyVolumeEth = BigDecimal.ZERO;

    @Column(name = "daily_volume_usd", nullable = false)
    private BigDecimal dailyVolumeUsd = BigDecimal.ZERO;

    @Column(name = "daily_txns", nullable = false)
    private Long dailyTxns = 0L;

    @Column(name = "total_liquidity_token", nullable = false)
    private BigDecimal totalLiquidityToken = BigDecimal.ZERO;

    @Column(name = "total_liquidity_eth", nullable = false)
    private BigDecimal totalLiquidityEth = BigDecimal.ZERO;

    @Column(name = "total_liquidity_usd", nullable = false)
    private BigDecimal totalLiquidityUsd = BigDecimal.ZERO;

    @Column(name = "price_usd", nullable = false)
    private BigDecimal priceUsd = BigDecimal.ZERO;

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

