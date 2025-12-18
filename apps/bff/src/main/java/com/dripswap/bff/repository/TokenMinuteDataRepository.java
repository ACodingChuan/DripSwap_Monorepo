package com.dripswap.bff.repository;

import com.dripswap.bff.entity.TokenMinuteData;
import com.dripswap.bff.entity.ChainEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TokenMinuteDataRepository extends JpaRepository<TokenMinuteData, ChainEntityId> {

    List<TokenMinuteData> findByChainIdAndTokenIdOrderByPeriodStartUnixDesc(String chainId, String tokenId);
}
