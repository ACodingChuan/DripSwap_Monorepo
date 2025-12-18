package com.dripswap.bff.sync;

import com.dripswap.bff.entity.PairDayData;
import com.dripswap.bff.repository.PairDayDataRepository;
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
public class PairDayDataSyncHandler {

    private final PairDayDataRepository pairDayDataRepository;

    @Transactional
    public void handlePairDayData(String chainId, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }

        List<PairDayData> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse pairDayData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            pairDayDataRepository.saveAll(rows);
            log.info("Saved {} pairDayData rows for chain: {}", rows.size(), chainId);
        }
    }

    private PairDayData parseRow(String chainId, JsonNode node) {
        PairDayData row = new PairDayData();
        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setDate(node.get("date").asInt());
        row.setPairAddress(node.get("pairAddress").asText().toLowerCase());
        row.setToken0Id(node.get("token0").get("id").asText().toLowerCase());
        row.setToken1Id(node.get("token1").get("id").asText().toLowerCase());
        row.setReserve0(parseBigDecimal(node, "reserve0"));
        row.setReserve1(parseBigDecimal(node, "reserve1"));
        row.setTotalSupply(node.hasNonNull("totalSupply") ? parseBigDecimal(node, "totalSupply") : null);
        row.setReserveUsd(parseBigDecimal(node, "reserveUSD"));
        row.setDailyVolumeToken0(parseBigDecimal(node, "dailyVolumeToken0"));
        row.setDailyVolumeToken1(parseBigDecimal(node, "dailyVolumeToken1"));
        row.setDailyVolumeUsd(parseBigDecimal(node, "dailyVolumeUSD"));
        row.setDailyTxns(parseLong(node, "dailyTxns"));
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

