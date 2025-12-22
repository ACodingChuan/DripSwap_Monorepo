package com.dripswap.bff.repository;

import com.dripswap.bff.entity.BridgeTransfer;
import com.dripswap.bff.entity.ChainEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BridgeTransferRepository extends JpaRepository<BridgeTransfer, ChainEntityId> {
    List<BridgeTransfer> findByChainId(String chainId, Pageable pageable);
}
