package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.BridgeTxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BridgeTxRepository extends JpaRepository<BridgeTxEntity, Long> {

    Optional<BridgeTxEntity> findByChainIdAndMessageId(String chainId, String messageId);
}
