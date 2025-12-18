package com.dripswap.bff.repository;

import com.dripswap.bff.entity.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncStatusRepository extends JpaRepository<SyncStatus, String> {}

