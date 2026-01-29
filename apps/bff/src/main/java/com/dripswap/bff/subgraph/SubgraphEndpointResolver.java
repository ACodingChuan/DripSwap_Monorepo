package com.dripswap.bff.subgraph;

import com.dripswap.bff.config.SubgraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Resolves subgraph endpoints from the configured chain router.
 */
@Component
@RequiredArgsConstructor
public class SubgraphEndpointResolver {

    private final SubgraphProperties subgraphProperties;

    public String resolveV2(String chainId) {
        if (chainId == null || chainId.isBlank()) return null;
        if (subgraphProperties == null || subgraphProperties.getChains() == null) return null;
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (chain == null) continue;
            if (!Objects.equals(chain.getId(), chainId)) continue;
            if (!chain.isEnabled()) continue;
            return chain.getEndpointV2();
        }
        return null;
    }
}
