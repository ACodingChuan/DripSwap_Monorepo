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
@Table(name = "mints")
public class Mint {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private Long timestamp = 0L;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(nullable = false)
    private BigDecimal liquidity = BigDecimal.ZERO;

    @Column
    private String sender;

    @Column
    private BigDecimal amount0;

    @Column
    private BigDecimal amount1;

    @Column(name = "log_index")
    private Long logIndex;

    @Column(name = "amount_usd")
    private BigDecimal amountUsd;

    @Column(name = "fee_to")
    private String feeTo;

    @Column(name = "fee_liquidity")
    private BigDecimal feeLiquidity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

