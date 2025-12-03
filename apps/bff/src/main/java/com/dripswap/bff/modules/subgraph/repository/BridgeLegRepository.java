package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.BridgeLegEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BridgeLegRepository extends JpaRepository<BridgeLegEntity, Long> {

    Optional<BridgeLegEntity> findByChainIdAndTxHashAndLogIndex(String chainId, String txHash, Integer logIndex);
}
