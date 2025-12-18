package com.dripswap.bff.repository;

import com.dripswap.bff.entity.Burn;
import com.dripswap.bff.entity.ChainEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BurnRepository extends JpaRepository<Burn, ChainEntityId> {}

