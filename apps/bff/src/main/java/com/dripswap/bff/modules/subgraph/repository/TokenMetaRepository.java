package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.TokenMetaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TokenMetaRepository extends JpaRepository<TokenMetaEntity, Long> {

    Optional<TokenMetaEntity> findByChainIdAndAddress(String chainId, String address);
}
