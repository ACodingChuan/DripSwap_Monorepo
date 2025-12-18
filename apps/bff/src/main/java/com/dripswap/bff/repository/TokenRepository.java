package com.dripswap.bff.repository;

import com.dripswap.bff.entity.Token;
import com.dripswap.bff.entity.ChainEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TokenRepository extends JpaRepository<Token, ChainEntityId> {
    
    List<Token> findByChainId(String chainId);
    
    Optional<Token> findByIdAndChainId(String id, String chainId);
    
    List<Token> findByChainIdOrderByTradeVolumeUsdDesc(String chainId);
}
