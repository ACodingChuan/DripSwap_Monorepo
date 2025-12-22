package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.Pair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PairRepository extends JpaRepository<Pair, ChainEntityId> {

    List<Pair> findByChainId(String chainId);

    Optional<Pair> findByIdAndChainId(String id, String chainId);

    List<Pair> findByChainIdAndIdIn(String chainId, List<String> ids);
}
