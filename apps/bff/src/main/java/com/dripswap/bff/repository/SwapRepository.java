package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.Swap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SwapRepository extends JpaRepository<Swap, ChainEntityId> {
    List<Swap> findByChainId(String chainId, Pageable pageable);

    List<Swap> findByChainIdAndPairIdInOrderByTimestampDesc(String chainId, List<String> pairIds, Pageable pageable);
}
