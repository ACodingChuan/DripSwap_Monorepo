package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Pair;
import com.dripswap.bff.repository.PairRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pair 数据同步处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PairSyncHandler {

    private final PairRepository pairRepository;

    @Transactional
    public void handlePairs(String chainId, JsonNode pairsNode) {
        if (pairsNode == null || !pairsNode.isArray()) {
            return;
        }

        List<Pair> pairs = new ArrayList<>();

        for (JsonNode node : pairsNode) {
            try {
                pairs.add(parsePair(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse pair: {}", node, e);
            }
        }

        if (!pairs.isEmpty()) {
            pairRepository.saveAll(pairs);
            log.info("Saved {} pairs for chain: {}", pairs.size(), chainId);
        }
    }

    private Pair parsePair(String chainId, JsonNode node) {
        Pair pair = new Pair();

        pair.setId(node.get("id").asText().toLowerCase());
        pair.setChainId(chainId);
        pair.setToken0Id(node.get("token0").get("id").asText().toLowerCase());
        pair.setToken1Id(node.get("token1").get("id").asText().toLowerCase());

        pair.setReserve0(parseBigDecimal(node, "reserve0"));
        pair.setReserve1(parseBigDecimal(node, "reserve1"));
        pair.setTotalSupply(parseBigDecimal(node, "totalSupply"));
        pair.setReserveEth(parseBigDecimal(node, "reserveETH"));
        pair.setReserveUsd(parseBigDecimal(node, "reserveUSD"));
        pair.setTrackedReserveEth(parseBigDecimal(node, "trackedReserveETH"));
        pair.setToken0Price(parseBigDecimal(node, "token0Price"));
        pair.setToken1Price(parseBigDecimal(node, "token1Price"));
        pair.setVolumeToken0(parseBigDecimal(node, "volumeToken0"));
        pair.setVolumeToken1(parseBigDecimal(node, "volumeToken1"));
        pair.setVolumeUsd(parseBigDecimal(node, "volumeUSD"));
        pair.setUntrackedVolumeUsd(parseBigDecimal(node, "untrackedVolumeUSD"));

        pair.setTxCount(parseLong(node, "txCount"));
        pair.setLiquidityProviderCount(parseLong(node, "liquidityProviderCount"));
        pair.setCreatedAtTimestamp(parseLong(node, "createdAtTimestamp"));
        pair.setCreatedAtBlockNumber(parseLong(node, "createdAtBlockNumber"));

        return pair;
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
