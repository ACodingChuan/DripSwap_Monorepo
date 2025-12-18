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
@Table(name = "swaps")
public class Swap {

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

    @Column(nullable = false)
    private String sender;

    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(name = "amount0_in", nullable = false)
    private BigDecimal amount0In = BigDecimal.ZERO;

    @Column(name = "amount1_in", nullable = false)
    private BigDecimal amount1In = BigDecimal.ZERO;

    @Column(name = "amount0_out", nullable = false)
    private BigDecimal amount0Out = BigDecimal.ZERO;

    @Column(name = "amount1_out", nullable = false)
    private BigDecimal amount1Out = BigDecimal.ZERO;

    @Column(name = "log_index")
    private Long logIndex;

    @Column(name = "amount_usd", nullable = false)
    private BigDecimal amountUsd = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

