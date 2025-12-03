package com.dripswap.bff.modules.subgraph.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "sync_cursor", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sync_cursor_chain_type", columnNames = {"chain_id", "data_type"})
})
public class SyncCursorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(name = "last_sync_block_number")
    private Long lastSyncBlockNumber;

    @Column(name = "last_sync_timestamp")
    private Long lastSyncTimestamp;

    @Column(name = "last_synced_id")
    private String lastSyncedId;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "error_count")
    private Integer errorCount;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    // getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Long getLastSyncBlockNumber() {
        return lastSyncBlockNumber;
    }

    public void setLastSyncBlockNumber(Long lastSyncBlockNumber) {
        this.lastSyncBlockNumber = lastSyncBlockNumber;
    }

    public Long getLastSyncTimestamp() {
        return lastSyncTimestamp;
    }

    public void setLastSyncTimestamp(Long lastSyncTimestamp) {
        this.lastSyncTimestamp = lastSyncTimestamp;
    }

    public String getLastSyncedId() {
        return lastSyncedId;
    }

    public void setLastSyncedId(String lastSyncedId) {
        this.lastSyncedId = lastSyncedId;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
