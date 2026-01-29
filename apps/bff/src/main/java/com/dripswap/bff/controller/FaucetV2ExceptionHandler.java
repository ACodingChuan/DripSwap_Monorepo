package com.dripswap.bff.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FaucetV2ExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "error", "BAD_REQUEST",
                "message", e.getMessage()
        );
    }
}
