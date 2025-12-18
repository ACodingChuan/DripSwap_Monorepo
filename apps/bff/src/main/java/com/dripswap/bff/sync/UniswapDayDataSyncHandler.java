package com.dripswap.bff.sync;

import com.dripswap.bff.entity.UniswapDayData;
import com.dripswap.bff.repository.UniswapDayDataRepository;
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
public class UniswapDayDataSyncHandler {

    private final UniswapDayDataRepository uniswapDayDataRepository;

    @Transactional
    public void handleUniswapDayData(String chainId, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }

        List<UniswapDayData> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse uniswapDayData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            uniswapDayDataRepository.saveAll(rows);
            log.info("Saved {} uniswapDayData rows for chain: {}", rows.size(), chainId);
        }
    }

    private UniswapDayData parseRow(String chainId, JsonNode node) {
        UniswapDayData row = new UniswapDayData();
        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setDate(node.get("date").asInt());
        row.setDailyVolumeEth(parseBigDecimal(node, "dailyVolumeETH"));
        row.setDailyVolumeUsd(parseBigDecimal(node, "dailyVolumeUSD"));
        row.setDailyVolumeUntracked(parseBigDecimal(node, "dailyVolumeUntracked"));
        row.setTotalVolumeEth(parseBigDecimal(node, "totalVolumeETH"));
        row.setTotalVolumeUsd(parseBigDecimal(node, "totalVolumeUSD"));
        row.setTotalLiquidityEth(parseBigDecimal(node, "totalLiquidityETH"));
        row.setTotalLiquidityUsd(parseBigDecimal(node, "totalLiquidityUSD"));
        row.setTxCount(parseLong(node, "txCount"));
        return row;
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

