package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.UniswapFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UniswapFactoryRepository extends JpaRepository<UniswapFactory, ChainEntityId> {
    Optional<UniswapFactory> findFirstByChainIdOrderByUpdatedAtDesc(String chainId);
}
