package com.dripswap.bff.repository;

import com.dripswap.bff.modules.chains.model.RawEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for raw blockchain events
 */
@Repository
public interface RawEventRepository extends JpaRepository<RawEvent, Long> {

    /**
     * Find event by chain_id, block_number, tx_hash, and log_index (unique key)
     */
    Optional<RawEvent> findByChainIdAndBlockNumberAndTxHashAndLogIndex(
            String chainId,
            Long blockNumber,
            String txHash,
            Integer logIndex
    );

    /**
     * Find all events by chain_id
     */
    List<RawEvent> findByChainId(String chainId);

    /**
     * Find all events by chain_id and block number range
     */
    List<RawEvent> findByChainIdAndBlockNumberBetween(
            String chainId,
            Long fromBlockNumber,
            Long toBlockNumber
    );

    /**
     * Find the latest block number for a chain
     */
    @Query(value = "SELECT MAX(block_number) FROM raw_events WHERE chain_id = ?1", nativeQuery = true)
    Optional<Long> findMaxBlockNumberByChainId(String chainId);

    /**
     * Find event by tx_hash and log_index (idempotency check)
     */
    Optional<RawEvent> findByTxHashAndLogIndex(String txHash, Integer logIndex);
}
