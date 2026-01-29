package com.dripswap.bff.controller.dto;

public record CcipBridgeRecordCreateRequest(
        String messageId,
        String userAddress,
        String tokenSymbol,
        String tokenAddress,
        long fromChainId,
        long toChainId,
        String sourceTxHash
) {}

