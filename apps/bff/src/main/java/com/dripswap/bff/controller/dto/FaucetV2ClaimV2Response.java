package com.dripswap.bff.controller.dto;

import java.util.UUID;

public record FaucetV2ClaimV2Response(
        UUID requestId,
        String status,
        String message,
        String txHash
) {}
