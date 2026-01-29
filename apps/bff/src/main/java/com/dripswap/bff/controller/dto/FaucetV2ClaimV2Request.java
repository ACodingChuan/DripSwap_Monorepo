package com.dripswap.bff.controller.dto;

public record FaucetV2ClaimV2Request(
        long chainId,
        String user,
        String idempotencyKey,
        String deviceId,
        String captchaId,
        String captchaAnswer,
        String token
) {}
