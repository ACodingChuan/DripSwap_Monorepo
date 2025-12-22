package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.TokenHourData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TokenHourDataRepository extends JpaRepository<TokenHourData, ChainEntityId> {
    Optional<TokenHourData> findFirstByChainIdAndTokenIdOrderByPeriodStartUnixDesc(String chainId, String tokenId);

    Optional<TokenHourData> findFirstByChainIdAndTokenIdAndPeriodStartUnixLessThanEqualOrderByPeriodStartUnixDesc(
            String chainId,
            String tokenId,
            Integer periodStartUnix
    );
    
    Optional<TokenHourData> findByChainIdAndTokenIdAndPeriodStartUnix(String chainId, String tokenId, Integer periodStartUnix);

    List<TokenHourData> findByChainIdAndTokenIdOrderByPeriodStartUnixDesc(
            String chainId,
            String tokenId,
            Pageable pageable
    );

    List<TokenHourData> findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
            String chainId,
            String tokenId,
            Integer from,
            Integer to
    );
}
