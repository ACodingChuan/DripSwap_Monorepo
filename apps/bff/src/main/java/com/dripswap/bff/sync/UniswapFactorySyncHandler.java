package com.dripswap.bff.sync;

import com.dripswap.bff.entity.UniswapFactory;
import com.dripswap.bff.repository.UniswapFactoryRepository;
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
public class UniswapFactorySyncHandler {

    private final UniswapFactoryRepository uniswapFactoryRepository;

    @Transactional
    public void handleFactories(String chainId, JsonNode factoriesNode) {
        if (factoriesNode == null || !factoriesNode.isArray()) {
            return;
        }

        List<UniswapFactory> factories = new ArrayList<>();
        for (JsonNode node : factoriesNode) {
            try {
                factories.add(parseFactory(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse uniswapFactory: {}", node, e);
            }
        }

        if (!factories.isEmpty()) {
            uniswapFactoryRepository.saveAll(factories);
            log.info("Saved {} uniswapFactories for chain: {}", factories.size(), chainId);
        }
    }

    private UniswapFactory parseFactory(String chainId, JsonNode node) {
        UniswapFactory factory = new UniswapFactory();
        factory.setId(node.get("id").asText().toLowerCase());
        factory.setChainId(chainId);
        factory.setPairCount(node.get("pairCount").asInt());
        factory.setTotalVolumeUsd(parseBigDecimal(node, "totalVolumeUSD"));
        factory.setTotalVolumeEth(parseBigDecimal(node, "totalVolumeETH"));
        factory.setUntrackedVolumeUsd(parseBigDecimal(node, "untrackedVolumeUSD"));
        factory.setTotalLiquidityUsd(parseBigDecimal(node, "totalLiquidityUSD"));
        factory.setTotalLiquidityEth(parseBigDecimal(node, "totalLiquidityETH"));
        factory.setTxCount(parseLong(node, "txCount"));
        return factory;
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

    private Long parseLong(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return 0L;
        }
        try {
            return new BigDecimal(field.asText()).longValue();
        } catch (Exception e) {
            log.warn("Failed to parse Long for field {}: {}", fieldName, field.asText());
            return 0L;
        }
    }
}

