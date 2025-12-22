package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.UniswapDayData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UniswapDayDataRepository extends JpaRepository<UniswapDayData, ChainEntityId> {
    Optional<UniswapDayData> findFirstByChainIdOrderByDateDesc(String chainId);

    List<UniswapDayData> findByChainIdOrderByDateDesc(String chainId, Pageable pageable);
}
