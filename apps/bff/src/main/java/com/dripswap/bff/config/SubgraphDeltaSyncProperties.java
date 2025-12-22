package com.dripswap.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Subgraph delta sync configuration.
 *
 * <p>These properties are optional and only used by the (optional) delta sync service.
 * Keeping them here prevents confusion when {@code application.yaml} contains {@code subgraph.delta.*}.
 */
@Component
@ConfigurationProperties(prefix = "subgraph.delta")
public class SubgraphDeltaSyncProperties {

    /**
     * Enable delta sync loop.
     */
    private boolean enabled = false;

    /**
     * Tick interval in milliseconds.
     */
    private long tickMs = 5_000;

    /**
     * Backoff schedule in seconds.
     */
    private List<Integer> intervalScheduleSeconds = new ArrayList<>();

    /**
     * Reset schedule when any progress is made.
     */
    private boolean resetOnChange = true;

    /**
     * Reset schedule on external signal (e.g. websocket event).
     */
    private boolean resetOnWsSignal = true;

    /**
     * Page size per subgraph request.
     */
    private int batchSize = 500;

    /**
     * Maximum number of pages per tick.
     */
    private int maxPagesPerTick = 2;

    /**
     * Maximum total records processed per tick.
     */
    private int maxTotalRecordsPerTick = 5_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public List<Integer> getIntervalScheduleSeconds() {
        return intervalScheduleSeconds;
    }

    public void setIntervalScheduleSeconds(List<Integer> intervalScheduleSeconds) {
        this.intervalScheduleSeconds = intervalScheduleSeconds;
    }

    public boolean isResetOnChange() {
        return resetOnChange;
    }

    public void setResetOnChange(boolean resetOnChange) {
        this.resetOnChange = resetOnChange;
    }

    public boolean isResetOnWsSignal() {
        return resetOnWsSignal;
    }

    public void setResetOnWsSignal(boolean resetOnWsSignal) {
        this.resetOnWsSignal = resetOnWsSignal;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxPagesPerTick() {
        return maxPagesPerTick;
    }

    public void setMaxPagesPerTick(int maxPagesPerTick) {
        this.maxPagesPerTick = maxPagesPerTick;
    }

    public int getMaxTotalRecordsPerTick() {
        return maxTotalRecordsPerTick;
    }

    public void setMaxTotalRecordsPerTick(int maxTotalRecordsPerTick) {
        this.maxTotalRecordsPerTick = maxTotalRecordsPerTick;
    }
}

