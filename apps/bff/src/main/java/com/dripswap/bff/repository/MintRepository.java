package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.Mint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MintRepository extends JpaRepository<Mint, ChainEntityId> {
    List<Mint> findByChainId(String chainId, Pageable pageable);
}
