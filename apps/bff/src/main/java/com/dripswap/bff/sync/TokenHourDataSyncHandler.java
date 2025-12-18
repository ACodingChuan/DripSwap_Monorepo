package com.dripswap.bff.sync;

import com.dripswap.bff.entity.TokenHourData;
import com.dripswap.bff.repository.TokenHourDataRepository;
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
public class TokenHourDataSyncHandler {

    private final TokenHourDataRepository tokenHourDataRepository;

    @Transactional
    public void handleTokenHourData(String chainId, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }

        List<TokenHourData> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse tokenHourData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            tokenHourDataRepository.saveAll(rows);
            log.info("Saved {} tokenHourData rows for chain: {}", rows.size(), chainId);
        }
    }

    private TokenHourData parseRow(String chainId, JsonNode node) {
        TokenHourData row = new TokenHourData();
        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setPeriodStartUnix(node.get("periodStartUnix").asInt());
        row.setTokenId(node.get("token").get("id").asText().toLowerCase());
        row.setVolume(parseBigDecimal(node, "volume"));
        row.setVolumeUsd(parseBigDecimal(node, "volumeUSD"));
        row.setUntrackedVolumeUsd(parseBigDecimal(node, "untrackedVolumeUSD"));
        row.setTotalValueLocked(parseBigDecimal(node, "totalValueLocked"));
        row.setTotalValueLockedUsd(parseBigDecimal(node, "totalValueLockedUSD"));
        row.setPriceUsd(parseBigDecimal(node, "priceUSD"));
        row.setFeesUsd(parseBigDecimal(node, "feesUSD"));
        row.setOpen(parseBigDecimal(node, "open"));
        row.setHigh(parseBigDecimal(node, "high"));
        row.setLow(parseBigDecimal(node, "low"));
        row.setClose(parseBigDecimal(node, "close"));
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
}

