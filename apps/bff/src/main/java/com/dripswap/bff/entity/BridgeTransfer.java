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
@Table(name = "bridge_transfers")
public class BridgeTransfer {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "tx_hash", nullable = false)
    private String txHash;

    @Column(name = "block_number", nullable = false)
    private Long blockNumber = 0L;

    @Column(nullable = false)
    private Long timestamp = 0L;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private String pool;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "dst_selector", nullable = false)
    private Long dstSelector = 0L;

    @Column(name = "receiver_chain_name", nullable = false)
    private String receiverChainName;

    @Column(nullable = false)
    private String receiver;

    @Column(name = "pay_in_link", nullable = false)
    private Boolean payInLink = false;

    @Column(name = "ccip_fee", nullable = false)
    private BigDecimal ccipFee = BigDecimal.ZERO;

    @Column(name = "service_fee_paid", nullable = false)
    private BigDecimal serviceFeePaid = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

