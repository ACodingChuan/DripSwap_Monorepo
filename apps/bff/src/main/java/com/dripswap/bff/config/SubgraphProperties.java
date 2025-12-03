package com.dripswap.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "subgraph")
public class SubgraphProperties {

    /**
     * 调度间隔（毫秒）
     */
    private long syncIntervalMs = 120_000;

    /**
     * 单次批量拉取条数
     */
    private int batchSize = 500;

    /**
     * 失败重试次数
     */
    private int retryCount = 3;

    /**
     * 多链子图配置
     */
    private List<ChainConfig> chains = new ArrayList<>();

    public long getSyncIntervalMs() {
        return syncIntervalMs;
    }

    public void setSyncIntervalMs(long syncIntervalMs) {
        this.syncIntervalMs = syncIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public List<ChainConfig> getChains() {
        return chains;
    }

    public void setChains(List<ChainConfig> chains) {
        this.chains = chains;
    }

    public static class ChainConfig {
        private String id;
        private boolean enabled = false;
        private String endpoint;
        private long startBlock = 0L;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public long getStartBlock() {
            return startBlock;
        }

        public void setStartBlock(long startBlock) {
            this.startBlock = startBlock;
        }
    }
}
