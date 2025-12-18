package com.dripswap.bff.sync;

import com.dripswap.bff.entity.PairHourData;
import com.dripswap.bff.repository.PairHourDataRepository;
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
public class PairHourDataSyncHandler {

    private final PairHourDataRepository pairHourDataRepository;

    @Transactional
    public void handlePairHourData(String chainId, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }

        List<PairHourData> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse pairHourData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            pairHourDataRepository.saveAll(rows);
            log.info("Saved {} pairHourData rows for chain: {}", rows.size(), chainId);
        }
    }

    private PairHourData parseRow(String chainId, JsonNode node) {
        PairHourData row = new PairHourData();
        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setHourStartUnix(node.get("hourStartUnix").asInt());
        row.setPairId(node.get("pair").get("id").asText().toLowerCase());
        row.setReserve0(parseBigDecimal(node, "reserve0"));
        row.setReserve1(parseBigDecimal(node, "reserve1"));
        row.setTotalSupply(node.hasNonNull("totalSupply") ? parseBigDecimal(node, "totalSupply") : null);
        row.setReserveUsd(parseBigDecimal(node, "reserveUSD"));
        row.setHourlyVolumeToken0(parseBigDecimal(node, "hourlyVolumeToken0"));
        row.setHourlyVolumeToken1(parseBigDecimal(node, "hourlyVolumeToken1"));
        row.setHourlyVolumeUsd(parseBigDecimal(node, "hourlyVolumeUSD"));
        row.setHourlyTxns(parseLong(node, "hourlyTxns"));
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

