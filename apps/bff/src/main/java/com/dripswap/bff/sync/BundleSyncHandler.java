package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Bundle;
import com.dripswap.bff.repository.BundleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BundleSyncHandler {

    private final BundleRepository bundleRepository;

    @Transactional
    public void handleBundles(String chainId, JsonNode bundlesNode) {
        if (bundlesNode == null || !bundlesNode.isArray()) {
            return;
        }

        List<Bundle> bundles = new ArrayList<>();
        for (JsonNode node : bundlesNode) {
            try {
                bundles.add(parseBundle(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse bundle: {}", node, e);
            }
        }

        if (!bundles.isEmpty()) {
            bundleRepository.saveAll(bundles);
            log.info("Saved {} bundles for chain: {}", bundles.size(), chainId);
        }
    }

    private Bundle parseBundle(String chainId, JsonNode node) {
        Bundle bundle = new Bundle();
        bundle.setId(node.get("id").asText());
        bundle.setChainId(chainId);
        bundle.setEthPrice(parseBigDecimal(node, "ethPrice"));
        return bundle;
    }

    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(field.asText());
        } catch (Exception e) {
            log.warn("Failed to parse BigDecimal for field {}: {}", fieldName, field.asText());
            return BigDecimal.ZERO;
        }
    }
}

