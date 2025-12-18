package com.dripswap.bff.sync;

import com.dripswap.bff.entity.TokenDayData;
import com.dripswap.bff.repository.TokenDayDataRepository;
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
public class TokenDayDataSyncHandler {

    private final TokenDayDataRepository tokenDayDataRepository;

    @Transactional
    public void handleTokenDayData(String chainId, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }

        List<TokenDayData> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse tokenDayData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            tokenDayDataRepository.saveAll(rows);
            log.info("Saved {} tokenDayData rows for chain: {}", rows.size(), chainId);
        }
    }

    private TokenDayData parseRow(String chainId, JsonNode node) {
        TokenDayData row = new TokenDayData();
        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setDate(node.get("date").asInt());
        row.setTokenId(node.get("token").get("id").asText().toLowerCase());
        row.setDailyVolumeToken(parseBigDecimal(node, "dailyVolumeToken"));
        row.setDailyVolumeEth(parseBigDecimal(node, "dailyVolumeETH"));
        row.setDailyVolumeUsd(parseBigDecimal(node, "dailyVolumeUSD"));
        row.setDailyTxns(parseLong(node, "dailyTxns"));
        row.setTotalLiquidityToken(parseBigDecimal(node, "totalLiquidityToken"));
        row.setTotalLiquidityEth(parseBigDecimal(node, "totalLiquidityETH"));
        row.setTotalLiquidityUsd(parseBigDecimal(node, "totalLiquidityUSD"));
        row.setPriceUsd(parseBigDecimal(node, "priceUSD"));
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

