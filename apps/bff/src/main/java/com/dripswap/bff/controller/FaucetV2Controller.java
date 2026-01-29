package com.dripswap.bff.controller;

import com.dripswap.bff.controller.dto.FaucetV2CaptchaResponse;
import com.dripswap.bff.controller.dto.FaucetV2ClaimV2Request;
import com.dripswap.bff.controller.dto.FaucetV2ClaimV2Response;
import com.dripswap.bff.service.FaucetV2CaptchaService;
import com.dripswap.bff.service.FaucetV2ClaimV2Service;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/faucet/v2")
public class FaucetV2Controller {
    private final FaucetV2CaptchaService captchaService;
    private final FaucetV2ClaimV2Service claimService;

    public FaucetV2Controller(
            FaucetV2CaptchaService captchaService,
            FaucetV2ClaimV2Service claimService
    ) {
        this.captchaService = captchaService;
        this.claimService = claimService;
    }

    @GetMapping("/captcha")
    public FaucetV2CaptchaResponse captcha(HttpServletRequest httpReq) {
        var challenge = captchaService.issue(clientIp(httpReq));
        return new FaucetV2CaptchaResponse(
                challenge.enabled(),
                challenge.captchaId(),
                challenge.imageData(),
                challenge.expiresInSeconds()
        );
    }

    @PostMapping("/claim2")
    public FaucetV2ClaimV2Response claim2(@RequestBody FaucetV2ClaimV2Request req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        return claimService.claim(req, ip);
    }

    private static String clientIp(HttpServletRequest req) {
        // Basic extraction; later can harden with trusted proxy list.
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
