package com.dripswap.bff.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FaucetV2ChainConfigRepository {
    ChainConfig findByChainId(@Param("chainId") long chainId);
}
