package com.dripswap.bff.sync;

import com.dripswap.bff.config.SubgraphDeltaSyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Delta sync service placeholder.
 *
 * <p>The repo currently uses {@link SubgraphSyncService} for full sync. Some environments may
 * configure {@code subgraph.delta.*} for an incremental sync strategy; this class keeps the wiring
 * point so the project can evolve without breaking config compatibility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubgraphDeltaSyncService {

    private final SubgraphDeltaSyncProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * No-op tick. Intentionally does not trigger any sync by default.
     * Implement real delta sync when the exact cursor model is confirmed.
     */
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        log.debug(
                "Subgraph delta sync enabled but not implemented (tickMs={}, batchSize={}, maxPagesPerTick={}, maxTotalRecordsPerTick={})",
                properties.getTickMs(),
                properties.getBatchSize(),
                properties.getMaxPagesPerTick(),
                properties.getMaxTotalRecordsPerTick()
        );
    }
}

