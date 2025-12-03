package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.SwapTxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SwapTxRepository extends JpaRepository<SwapTxEntity, Long> {

    Optional<SwapTxEntity> findByChainIdAndTxHashAndLogIndex(String chainId, String txHash, Integer logIndex);
}
