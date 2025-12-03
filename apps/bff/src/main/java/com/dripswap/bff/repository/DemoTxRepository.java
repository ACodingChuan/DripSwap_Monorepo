package com.dripswap.bff.repository;

import com.dripswap.bff.modules.rest.model.DemoTx;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for demo transactions.
 */
@Repository
public interface DemoTxRepository extends JpaRepository<DemoTx, Long> {

    /**
     * Find demo transaction by tx_hash.
     */
    Optional<DemoTx> findByTxHash(String txHash);

    /**
     * Find demo transactions by chain_id.
     */
    List<DemoTx> findByChainId(String chainId);

    /**
     * Find demo transactions by status.
     */
    List<DemoTx> findByStatus(String status);

    /**
     * Find demo transactions by chain_id and status.
     */
    List<DemoTx> findByChainIdAndStatus(String chainId, String status);
}
