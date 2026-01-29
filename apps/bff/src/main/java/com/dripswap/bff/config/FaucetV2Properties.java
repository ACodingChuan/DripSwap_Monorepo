package com.dripswap.bff.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "faucetv2")
public class FaucetV2Properties {
    private String calibrationPath;
    private String ipHashSalt;
    private List<Chain> chains = new ArrayList<>();

    // === P0-6/P0-7 risk-control knobs (reasonable defaults; override via env) ===
    private int walletDailyMaxSingle = 3;
    private int walletCooldownSeconds = 600;
    private int claimDeadlineSeconds = 600;

    private int ipHourlyMaxClaims = 9;
    // PRD default: 45/day, 5 unique wallets/day.
    private int ipDailyMaxClaims = 45;
    private int ipDailyMaxUniqueWallets = 5;

    private Captcha captcha = new Captcha();
    private Cache cache = new Cache();

    public String getCalibrationPath() {
        return calibrationPath;
    }

    public void setCalibrationPath(String calibrationPath) {
        this.calibrationPath = calibrationPath;
    }

    public String getIpHashSalt() {
        return ipHashSalt;
    }

    public void setIpHashSalt(String ipHashSalt) {
        this.ipHashSalt = ipHashSalt;
    }

    public List<Chain> getChains() {
        return chains;
    }

    public void setChains(List<Chain> chains) {
        this.chains = chains;
    }

    public int getWalletDailyMaxSingle() {
        return walletDailyMaxSingle;
    }

    public void setWalletDailyMaxSingle(int walletDailyMaxSingle) {
        this.walletDailyMaxSingle = walletDailyMaxSingle;
    }

    public int getWalletCooldownSeconds() {
        return walletCooldownSeconds;
    }

    public void setWalletCooldownSeconds(int walletCooldownSeconds) {
        this.walletCooldownSeconds = walletCooldownSeconds;
    }

    public int getClaimDeadlineSeconds() {
        return claimDeadlineSeconds;
    }

    public void setClaimDeadlineSeconds(int claimDeadlineSeconds) {
        this.claimDeadlineSeconds = claimDeadlineSeconds;
    }

    public int getIpHourlyMaxClaims() {
        return ipHourlyMaxClaims;
    }

    public void setIpHourlyMaxClaims(int ipHourlyMaxClaims) {
        this.ipHourlyMaxClaims = ipHourlyMaxClaims;
    }

    public int getIpDailyMaxClaims() {
        return ipDailyMaxClaims;
    }

    public void setIpDailyMaxClaims(int ipDailyMaxClaims) {
        this.ipDailyMaxClaims = ipDailyMaxClaims;
    }

    public int getIpDailyMaxUniqueWallets() {
        return ipDailyMaxUniqueWallets;
    }

    public void setIpDailyMaxUniqueWallets(int ipDailyMaxUniqueWallets) {
        this.ipDailyMaxUniqueWallets = ipDailyMaxUniqueWallets;
    }

    public Captcha getCaptcha() {
        return captcha;
    }

    public void setCaptcha(Captcha captcha) {
        this.captcha = captcha;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public static class Captcha {
        private boolean enabled = false;
        private int ttlSeconds = 300;
        private int length = 5;
        private String secretKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }
    }

    public static class Cache {
        private int relayerBalanceTtlSeconds = 86400;
        private int stateTtlSeconds = 345600;

        public int getRelayerBalanceTtlSeconds() {
            return relayerBalanceTtlSeconds;
        }

        public void setRelayerBalanceTtlSeconds(int relayerBalanceTtlSeconds) {
            this.relayerBalanceTtlSeconds = relayerBalanceTtlSeconds;
        }

        public int getStateTtlSeconds() {
            return stateTtlSeconds;
        }

        public void setStateTtlSeconds(int stateTtlSeconds) {
            this.stateTtlSeconds = stateTtlSeconds;
        }
    }

    public static class Chain {
        private long chainId;
        private boolean enabled = true;
        private String rpcUrl;
        private String faucetAddress;
        private String addressBookPath;
        private String signerPrivateKey;
        private String relayerPrivateKey;
        private boolean paused = false;

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

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }

        public String getFaucetAddress() {
            return faucetAddress;
        }

        public void setFaucetAddress(String faucetAddress) {
            this.faucetAddress = faucetAddress;
        }

        public String getAddressBookPath() {
            return addressBookPath;
        }

        public void setAddressBookPath(String addressBookPath) {
            this.addressBookPath = addressBookPath;
        }

        public String getSignerPrivateKey() {
            return signerPrivateKey;
        }

        public void setSignerPrivateKey(String signerPrivateKey) {
            this.signerPrivateKey = signerPrivateKey;
        }

        public String getRelayerPrivateKey() {
            return relayerPrivateKey;
        }

        public void setRelayerPrivateKey(String relayerPrivateKey) {
            this.relayerPrivateKey = relayerPrivateKey;
        }

        public boolean isPaused() {
            return paused;
        }

        public void setPaused(boolean paused) {
            this.paused = paused;
        }
    }
}
