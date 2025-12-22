package com.dripswap.bff.repository;

import com.dripswap.bff.entity.Burn;
import com.dripswap.bff.entity.ChainEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BurnRepository extends JpaRepository<Burn, ChainEntityId> {
    List<Burn> findByChainId(String chainId, Pageable pageable);
}
