package com.dripswap.bff.repository;

import java.math.BigInteger;

public class RollbackRow {
    private long chainId;
    private String userAddress;
    private String tokenAddress;
    private BigInteger amountRaw;
    private long day;
    private String ipHash;

    public long getChainId() {
        return chainId;
    }

    public void setChainId(long chainId) {
        this.chainId = chainId;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public String getTokenAddress() {
        return tokenAddress;
    }

    public void setTokenAddress(String tokenAddress) {
        this.tokenAddress = tokenAddress;
    }

    public BigInteger getAmountRaw() {
        return amountRaw;
    }

    public void setAmountRaw(BigInteger amountRaw) {
        this.amountRaw = amountRaw;
    }

    public long getDay() {
        return day;
    }

    public void setDay(long day) {
        this.day = day;
    }

    public String getIpHash() {
        return ipHash;
    }

    public void setIpHash(String ipHash) {
        this.ipHash = ipHash;
    }
}
