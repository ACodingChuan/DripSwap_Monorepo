package com.dripswap.bff.modules.subgraph.repository;

import com.dripswap.bff.modules.subgraph.model.SyncCursorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SyncCursorRepository extends JpaRepository<SyncCursorEntity, Long> {

    Optional<SyncCursorEntity> findByChainIdAndDataType(String chainId, String dataType);
}
