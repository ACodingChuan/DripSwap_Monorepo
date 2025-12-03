package com.dripswap.bff.modules.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * User portfolio DTO.
 */
public class UserPortfolio {

    private String address;
    private BigDecimal netFlowUSD;
    private List<String> recentSwaps;

    // Constructors
    public UserPortfolio() {
    }

    public UserPortfolio(String address, BigDecimal netFlowUSD, List<String> recentSwaps) {
        this.address = address;
        this.netFlowUSD = netFlowUSD;
        this.recentSwaps = recentSwaps;
    }

    // Getters and Setters
    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getNetFlowUSD() {
        return netFlowUSD;
    }

    public void setNetFlowUSD(BigDecimal netFlowUSD) {
        this.netFlowUSD = netFlowUSD;
    }

    public List<String> getRecentSwaps() {
        return recentSwaps;
    }

    public void setRecentSwaps(List<String> recentSwaps) {
        this.recentSwaps = recentSwaps;
    }

    @Override
    public String toString() {
        return "UserPortfolio{" +
                "address='" + address + '\'' +
                ", netFlowUSD=" + netFlowUSD +
                ", recentSwaps=" + recentSwaps +
                '}';
    }
}
