package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.PairTokenLookup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PairTokenLookupRepository extends JpaRepository<PairTokenLookup, ChainEntityId> {
    List<PairTokenLookup> findByChainIdAndIdStartingWith(String chainId, String prefix, Pageable pageable);
}
