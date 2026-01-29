package com.dripswap.bff.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FaucetV2RiskRepository {
    UserIpSnapshot loadUserIpSnapshot(
            @Param("chainId") long chainId,
            @Param("user") String user,
            @Param("ipHash") String ipHash,
            @Param("day") long day
    );
}
