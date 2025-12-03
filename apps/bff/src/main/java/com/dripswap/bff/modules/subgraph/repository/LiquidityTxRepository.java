package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.LiquidityTxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LiquidityTxRepository extends JpaRepository<LiquidityTxEntity, Long> {

    Optional<LiquidityTxEntity> findByChainIdAndTxHashAndLogIndex(String chainId, String txHash, Integer logIndex);
}
