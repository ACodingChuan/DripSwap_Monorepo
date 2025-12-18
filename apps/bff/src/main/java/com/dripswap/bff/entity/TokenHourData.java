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
@Table(name = "token_hour_data")
public class TokenHourData {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "period_start_unix", nullable = false)
    private Integer periodStartUnix = 0;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(nullable = false)
    private BigDecimal volume = BigDecimal.ZERO;

    @Column(name = "volume_usd", nullable = false)
    private BigDecimal volumeUsd = BigDecimal.ZERO;

    @Column(name = "untracked_volume_usd", nullable = false)
    private BigDecimal untrackedVolumeUsd = BigDecimal.ZERO;

    @Column(name = "total_value_locked", nullable = false)
    private BigDecimal totalValueLocked = BigDecimal.ZERO;

    @Column(name = "total_value_locked_usd", nullable = false)
    private BigDecimal totalValueLockedUsd = BigDecimal.ZERO;

    @Column(name = "price_usd", nullable = false)
    private BigDecimal priceUsd = BigDecimal.ZERO;

    @Column(name = "fees_usd", nullable = false)
    private BigDecimal feesUsd = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal open = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal high = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal low = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal close = BigDecimal.ZERO;

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

