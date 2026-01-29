package com.dripswap.bff.repository;

public class ChainConfig {
    private long chainId;
    private boolean enabled;
    private boolean paused;
    private int userDailyMax;
    private int userCooldownSeconds;
    private int claimDeadlineSeconds;
    private int ipDailyMax;
    private String eip712Name;
    private String eip712Version;
    private String signerAddress;
    private String relayerAddress;

    public long getChainId() {
        return chainId;
    }

    public void setChainId(long chainId) {
        this.chainId = chainId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public int getUserDailyMax() {
        return userDailyMax;
    }

    public void setUserDailyMax(int userDailyMax) {
        this.userDailyMax = userDailyMax;
    }

    public int getUserCooldownSeconds() {
        return userCooldownSeconds;
    }

    public void setUserCooldownSeconds(int userCooldownSeconds) {
        this.userCooldownSeconds = userCooldownSeconds;
    }

    public int getClaimDeadlineSeconds() {
        return claimDeadlineSeconds;
    }

    public void setClaimDeadlineSeconds(int claimDeadlineSeconds) {
        this.claimDeadlineSeconds = claimDeadlineSeconds;
    }

    public int getIpDailyMax() {
        return ipDailyMax;
    }

    public void setIpDailyMax(int ipDailyMax) {
        this.ipDailyMax = ipDailyMax;
    }

    public String getEip712Name() {
        return eip712Name;
    }

    public void setEip712Name(String eip712Name) {
        this.eip712Name = eip712Name;
    }

    public String getEip712Version() {
        return eip712Version;
    }

    public void setEip712Version(String eip712Version) {
        this.eip712Version = eip712Version;
    }

    public String getSignerAddress() {
        return signerAddress;
    }

    public void setSignerAddress(String signerAddress) {
        this.signerAddress = signerAddress;
    }

    public String getRelayerAddress() {
        return relayerAddress;
    }

    public void setRelayerAddress(String relayerAddress) {
        this.relayerAddress = relayerAddress;
    }
}
