package com.dripswap.bff.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@IdClass(ChainEntityId.class)
@Table(name = "tokens")
public class Token {
    
    @Id
    private String id; // Token address (lowercase)

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false)
    private Integer decimals;
    
    @Column(name = "total_supply", nullable = false)
    private BigDecimal totalSupply = BigDecimal.ZERO;
    
    @Column(name = "trade_volume", nullable = false)
    private BigDecimal tradeVolume = BigDecimal.ZERO;
    
    @Column(name = "trade_volume_usd", nullable = false)
    private BigDecimal tradeVolumeUsd = BigDecimal.ZERO;
    
    @Column(name = "untracked_volume_usd", nullable = false)
    private BigDecimal untrackedVolumeUsd = BigDecimal.ZERO;
    
    @Column(name = "tx_count", nullable = false)
    private Long txCount = 0L;
    
    @Column(name = "total_liquidity", nullable = false)
    private BigDecimal totalLiquidity = BigDecimal.ZERO;
    
    @Column(name = "derived_eth", nullable = false)
    private BigDecimal derivedEth = BigDecimal.ZERO;
    
    // V2-tokens archive fields
    @Column(name = "last_minute_archived", nullable = false)
    private Long lastMinuteArchived = 0L;
    
    @Column(name = "last_hour_archived", nullable = false)
    private Long lastHourArchived = 0L;
    
    @Column(name = "minute_array", nullable = false, columnDefinition = "TEXT")
    private String minuteArray = "[]";
    
    @Column(name = "hour_array", nullable = false, columnDefinition = "TEXT")
    private String hourArray = "[]";
    
    @Column(name = "last_minute_recorded", nullable = false)
    private Long lastMinuteRecorded = 0L;
    
    @Column(name = "last_hour_recorded", nullable = false)
    private Long lastHourRecorded = 0L;
    
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
