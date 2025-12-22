package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.TokenDayData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TokenDayDataRepository extends JpaRepository<TokenDayData, ChainEntityId> {
    Optional<TokenDayData> findFirstByChainIdAndTokenIdOrderByDateDesc(String chainId, String tokenId);

    Optional<TokenDayData> findFirstByChainIdAndTokenIdAndDateLessThanEqualOrderByDateDesc(
            String chainId,
            String tokenId,
            Integer date
    );

    List<TokenDayData> findByChainIdAndTokenIdAndDateIn(String chainId, String tokenId, List<Integer> dates);
}
