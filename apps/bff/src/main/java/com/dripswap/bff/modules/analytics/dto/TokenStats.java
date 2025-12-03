package com.dripswap.bff.modules.analytics.dto;

import java.math.BigDecimal;

/**
 * Token statistics DTO.
 */
public class TokenStats {

    private String token;
    private BigDecimal volume24h;
    private BigDecimal volume7d;
    private BigDecimal liquidityUSD;
    private Integer holders;

    // Constructors
    public TokenStats() {
    }

    public TokenStats(String token, BigDecimal volume24h, BigDecimal volume7d,
                     BigDecimal liquidityUSD, Integer holders) {
        this.token = token;
        this.volume24h = volume24h;
        this.volume7d = volume7d;
        this.liquidityUSD = liquidityUSD;
        this.holders = holders;
    }

    // Getters and Setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public BigDecimal getVolume24h() {
        return volume24h;
    }

    public void setVolume24h(BigDecimal volume24h) {
        this.volume24h = volume24h;
    }

    public BigDecimal getVolume7d() {
        return volume7d;
    }

    public void setVolume7d(BigDecimal volume7d) {
        this.volume7d = volume7d;
    }

    public BigDecimal getLiquidityUSD() {
        return liquidityUSD;
    }

    public void setLiquidityUSD(BigDecimal liquidityUSD) {
        this.liquidityUSD = liquidityUSD;
    }

    public Integer getHolders() {
        return holders;
    }

    public void setHolders(Integer holders) {
        this.holders = holders;
    }

    @Override
    public String toString() {
        return "TokenStats{" +
                "token='" + token + '\'' +
                ", volume24h=" + volume24h +
                ", volume7d=" + volume7d +
                ", liquidityUSD=" + liquidityUSD +
                ", holders=" + holders +
                '}';
    }
}
