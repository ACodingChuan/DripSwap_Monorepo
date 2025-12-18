package com.dripswap.bff.sync;

import com.dripswap.bff.entity.TokenMinuteData;
import com.dripswap.bff.repository.TokenMinuteDataRepository;
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
public class TokenMinuteDataSyncHandler {

    private final TokenMinuteDataRepository tokenMinuteDataRepository;

    @Transactional
    public void handleTokenMinuteData(String chainId, JsonNode tokenMinuteDatasNode) {
        if (tokenMinuteDatasNode == null || !tokenMinuteDatasNode.isArray()) {
            return;
        }

        List<TokenMinuteData> rows = new ArrayList<>();

        for (JsonNode node : tokenMinuteDatasNode) {
            try {
                rows.add(parseRow(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse tokenMinuteData: {}", node, e);
            }
        }

        if (!rows.isEmpty()) {
            tokenMinuteDataRepository.saveAll(rows);
            log.info("Saved {} tokenMinuteData rows for chain: {}", rows.size(), chainId);
        }
    }

    private TokenMinuteData parseRow(String chainId, JsonNode node) {
        TokenMinuteData row = new TokenMinuteData();

        row.setId(node.get("id").asText());
        row.setChainId(chainId);
        row.setPeriodStartUnix(node.get("periodStartUnix").asInt());

        JsonNode tokenNode = node.get("token");
        if (tokenNode != null && tokenNode.hasNonNull("id")) {
            row.setTokenId(tokenNode.get("id").asText().toLowerCase());
        } else if (node.hasNonNull("tokenId")) {
            row.setTokenId(node.get("tokenId").asText().toLowerCase());
        } else {
            throw new IllegalArgumentException("token id is missing");
        }

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
