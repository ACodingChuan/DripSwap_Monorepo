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
@Table(name = "pair_hour_data")
public class PairHourData {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "hour_start_unix", nullable = false)
    private Integer hourStartUnix = 0;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(nullable = false)
    private BigDecimal reserve0 = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal reserve1 = BigDecimal.ZERO;

    @Column(name = "total_supply")
    private BigDecimal totalSupply;

    @Column(name = "reserve_usd", nullable = false)
    private BigDecimal reserveUsd = BigDecimal.ZERO;

    @Column(name = "hourly_volume_token0", nullable = false)
    private BigDecimal hourlyVolumeToken0 = BigDecimal.ZERO;

    @Column(name = "hourly_volume_token1", nullable = false)
    private BigDecimal hourlyVolumeToken1 = BigDecimal.ZERO;

    @Column(name = "hourly_volume_usd", nullable = false)
    private BigDecimal hourlyVolumeUsd = BigDecimal.ZERO;

    @Column(name = "hourly_txns", nullable = false)
    private Long hourlyTxns = 0L;

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

