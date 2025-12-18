package com.dripswap.bff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@IdClass(ChainEntityId.class)
@Table(name = "bridge_config_events")
public class BridgeConfigEvent {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column
    private String token;

    @Column
    private String pool;

    @Column(name = "min_amount")
    private BigDecimal minAmount;

    @Column(name = "max_amount")
    private BigDecimal maxAmount;

    @Column(name = "native_allowed")
    private Boolean nativeAllowed;

    @Column(name = "link_allowed")
    private Boolean linkAllowed;

    @Column(name = "new_fee")
    private BigDecimal newFee;

    @Column(name = "new_collector")
    private String newCollector;

    @Column(name = "block_number", nullable = false)
    private Long blockNumber = 0L;

    @Column(nullable = false)
    private Long timestamp = 0L;

    @Column(name = "transaction_hash", nullable = false)
    private String transactionHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

