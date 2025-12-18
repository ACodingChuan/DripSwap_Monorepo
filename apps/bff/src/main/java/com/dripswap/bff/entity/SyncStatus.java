package com.dripswap.bff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sync_status")
public class SyncStatus {

    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "last_synced_block")
    private Long lastSyncedBlock;

    @Column(name = "last_synced_timestamp")
    private Integer lastSyncedTimestamp;

    @Column(name = "last_synced_id")
    private String lastSyncedId;

    @Column(name = "sync_start_time")
    private LocalDateTime syncStartTime;

    @Column(name = "sync_end_time")
    private LocalDateTime syncEndTime;

    @Column(name = "sync_status", nullable = false)
    private String syncStatus = "pending";

    @Column(name = "error_message")
    private String errorMessage;

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

