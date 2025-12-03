package com.dripswap.bff.modules.analytics.dto;

import java.math.BigDecimal;

/**
 * TVL statistics DTO.
 */
public class TvlStats {

    private BigDecimal tvl;
    private BigDecimal tvl24hAgo;
    private BigDecimal changeRate;

    // Constructors
    public TvlStats() {
    }

    public TvlStats(BigDecimal tvl, BigDecimal tvl24hAgo, BigDecimal changeRate) {
        this.tvl = tvl;
        this.tvl24hAgo = tvl24hAgo;
        this.changeRate = changeRate;
    }

    // Getters and Setters
    public BigDecimal getTvl() {
        return tvl;
    }

    public void setTvl(BigDecimal tvl) {
        this.tvl = tvl;
    }

    public BigDecimal getTvl24hAgo() {
        return tvl24hAgo;
    }

    public void setTvl24hAgo(BigDecimal tvl24hAgo) {
        this.tvl24hAgo = tvl24hAgo;
    }

    public BigDecimal getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(BigDecimal changeRate) {
        this.changeRate = changeRate;
    }

    @Override
    public String toString() {
        return "TvlStats{" +
                "tvl=" + tvl +
                ", tvl24hAgo=" + tvl24hAgo +
                ", changeRate=" + changeRate +
                '}';
    }
}
