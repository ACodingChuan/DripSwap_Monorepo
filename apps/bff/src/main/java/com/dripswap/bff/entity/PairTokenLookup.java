package com.dripswap.bff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@IdClass(ChainEntityId.class)
@Table(name = "pair_token_lookup")
public class PairTokenLookup {

    @Id
    private String id;

    @Id
    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

