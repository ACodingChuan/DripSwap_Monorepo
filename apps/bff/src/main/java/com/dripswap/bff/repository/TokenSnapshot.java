package com.dripswap.bff.repository;

import java.math.BigInteger;

public class TokenSnapshot {
    private String tokenAddress;
    private String symbol;
    private BigInteger singleAmountRaw;
    private boolean whitelist;
    private BigInteger vaultBalanceRaw;
    private BigInteger issuedRaw;

    public String getTokenAddress() {
        return tokenAddress;
    }

    public void setTokenAddress(String tokenAddress) {
        this.tokenAddress = tokenAddress;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigInteger getSingleAmountRaw() {
        return singleAmountRaw;
    }

    public void setSingleAmountRaw(BigInteger singleAmountRaw) {
        this.singleAmountRaw = singleAmountRaw;
    }

    public boolean isWhitelist() {
        return whitelist;
    }

    public void setWhitelist(boolean whitelist) {
        this.whitelist = whitelist;
    }

    public BigInteger getVaultBalanceRaw() {
        return vaultBalanceRaw;
    }

    public void setVaultBalanceRaw(BigInteger vaultBalanceRaw) {
        this.vaultBalanceRaw = vaultBalanceRaw;
    }

    public BigInteger getIssuedRaw() {
        return issuedRaw;
    }

    public void setIssuedRaw(BigInteger issuedRaw) {
        this.issuedRaw = issuedRaw;
    }
}
