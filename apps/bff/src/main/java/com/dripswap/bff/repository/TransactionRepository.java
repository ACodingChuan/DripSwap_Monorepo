package com.dripswap.bff.repository;

import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, ChainEntityId> {
    List<Transaction> findByChainIdAndIdIn(String chainId, Collection<String> ids);
}
