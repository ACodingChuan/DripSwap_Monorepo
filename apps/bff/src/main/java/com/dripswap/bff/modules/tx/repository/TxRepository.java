package com.dripswap.bff.modules.tx.repository;

import com.dripswap.bff.modules.tx.model.TxRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for transaction records.
 */
@Repository
public interface TxRepository extends JpaRepository<TxRecord, Long> {

    /**
     * Find transaction by tx_hash (idempotency check).
     */
    Optional<TxRecord> findByTxHash(String txHash);

    /**
     * Find transaction by chain_id and tx_hash.
     */
    Optional<TxRecord> findByChainIdAndTxHash(String chainId, String txHash);

    /**
     * Find all transactions by chain_id.
     */
    List<TxRecord> findByChainId(String chainId);

    /**
     * Find all transactions by status.
     */
    List<TxRecord> findByStatus(String status);

    /**
     * Find all transactions by chain_id and status.
     */
    List<TxRecord> findByChainIdAndStatus(String chainId, String status);

    /**
     * Find all transactions by decoded_name.
     */
    List<TxRecord> findByDecodedName(String decodedName);

    /**
     * Count transactions by status.
     */
    long countByStatus(String status);

    /**
     * Count transactions by chain_id and status.
     */
    long countByChainIdAndStatus(String chainId, String status);
}
