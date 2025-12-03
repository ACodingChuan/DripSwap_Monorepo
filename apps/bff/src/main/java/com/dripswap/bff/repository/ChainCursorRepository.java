package com.dripswap.bff.repository;

import com.dripswap.bff.modules.chains.model.ChainCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for chain cursor data
 */
@Repository
public interface ChainCursorRepository extends JpaRepository<ChainCursor, Long> {

    /**
     * Find chain cursor by chain_id
     */
    Optional<ChainCursor> findByChainId(String chainId);
}
