package com.dripswap.bff.repository;

import com.dripswap.bff.entity.SyncError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncErrorRepository extends JpaRepository<SyncError, Long> {}

