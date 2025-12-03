package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.PairCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PairCacheRepository extends JpaRepository<PairCacheEntity, Long> {

    Optional<PairCacheEntity> findByChainIdAndAddress(String chainId, String address);
}
