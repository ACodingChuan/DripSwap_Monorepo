package com.dripswap.bff.controller.dto;

public record FaucetV2CaptchaResponse(
        boolean enabled,
        String captchaId,
        String imageData,
        int expiresInSeconds
) {}
