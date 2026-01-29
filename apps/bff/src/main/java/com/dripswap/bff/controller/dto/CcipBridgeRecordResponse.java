package com.dripswap.bff.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record CcipBridgeRecordResponse(
        UUID id,
        String messageId,
        String userAddress,
        String tokenSymbol,
        String tokenAddress,
        long fromChainId,
        long toChainId,
        String sourceTxHash,
        Instant createdAt
) {}

