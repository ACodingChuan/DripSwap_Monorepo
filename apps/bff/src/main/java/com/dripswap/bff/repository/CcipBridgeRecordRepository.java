package com.dripswap.bff.repository;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CcipBridgeRecordRepository {
    int insert(CcipBridgeRecord record);

    CcipBridgeRecord findByMessageId(@Param("messageId") String messageId);

    List<CcipBridgeRecord> findByUserAddress(@Param("userAddress") String userAddress, @Param("limit") int limit);

    int touchUpdatedAt(@Param("id") UUID id);
}

