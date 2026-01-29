package com.dripswap.bff.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FaucetV2TokenRepository {
    TokenSnapshot findTokenSnapshot(
            @Param("chainId") long chainId,
            @Param("token") String token,
            @Param("symbol") String symbol,
            @Param("day") long day
    );
}
