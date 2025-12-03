package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.VTokenStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VTokenStateRepository extends JpaRepository<VTokenStateEntity, Long> {

    Optional<VTokenStateEntity> findByChainIdAndAddress(String chainId, String address);
}
