package com.dripswap.bff.modules.analytics.dto;

import java.math.BigDecimal;

/**
 * Pair statistics DTO.
 */
public class PairStats {

    private String pair;
    private BigDecimal price;
    private BigDecimal reserve0;
    private BigDecimal reserve1;
    private BigDecimal volume24h;
    private BigDecimal volume7d;
    private BigDecimal tvl;
    private BigDecimal feeApr;

    // Constructors
    public PairStats() {
    }

    public PairStats(String pair, BigDecimal price, BigDecimal reserve0, BigDecimal reserve1,
                    BigDecimal volume24h, BigDecimal volume7d, BigDecimal tvl, BigDecimal feeApr) {
        this.pair = pair;
        this.price = price;
        this.reserve0 = reserve0;
        this.reserve1 = reserve1;
        this.volume24h = volume24h;
        this.volume7d = volume7d;
        this.tvl = tvl;
        this.feeApr = feeApr;
    }

    // Getters and Setters
    public String getPair() {
        return pair;
    }

    public void setPair(String pair) {
        this.pair = pair;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getReserve0() {
        return reserve0;
    }

    public void setReserve0(BigDecimal reserve0) {
        this.reserve0 = reserve0;
    }

    public BigDecimal getReserve1() {
        return reserve1;
    }

    public void setReserve1(BigDecimal reserve1) {
        this.reserve1 = reserve1;
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

    public BigDecimal getTvl() {
        return tvl;
    }

    public void setTvl(BigDecimal tvl) {
        this.tvl = tvl;
    }

    public BigDecimal getFeeApr() {
        return feeApr;
    }

    public void setFeeApr(BigDecimal feeApr) {
        this.feeApr = feeApr;
    }

    @Override
    public String toString() {
        return "PairStats{" +
                "pair='" + pair + '\'' +
                ", price=" + price +
                ", reserve0=" + reserve0 +
                ", reserve1=" + reserve1 +
                ", volume24h=" + volume24h +
                ", volume7d=" + volume7d +
                ", tvl=" + tvl +
                ", feeApr=" + feeApr +
                '}';
    }
}
