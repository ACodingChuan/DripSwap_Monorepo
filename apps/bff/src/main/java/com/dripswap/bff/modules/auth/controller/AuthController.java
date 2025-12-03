package com.dripswap.bff.modules.auth.controller;

import com.dripswap.bff.modules.auth.dto.LoginRequest;
import com.dripswap.bff.modules.auth.dto.LoginResponse;
import com.dripswap.bff.modules.auth.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/session")
@CrossOrigin(origins = "*") // Allow all for dev
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/nonce")
    public ResponseEntity<String> getNonce(@RequestParam String address) {
        String nonce = authService.generateNonce(address);
        return ResponseEntity.ok(nonce);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            String sessionId = authService.createSession(
                request.getAddress(), 
                request.getNonce(), 
                request.getSignature()
            );
            return ResponseEntity.ok(new LoginResponse(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
