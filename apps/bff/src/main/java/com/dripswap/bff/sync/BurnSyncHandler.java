package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Burn;
import com.dripswap.bff.entity.Transaction;
import com.dripswap.bff.repository.BurnRepository;
import com.dripswap.bff.repository.TransactionRepository;
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
public class BurnSyncHandler {

    private final BurnRepository burnRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void handleBurns(String chainId, JsonNode burnsNode) {
        if (burnsNode == null || !burnsNode.isArray()) {
            return;
        }

        List<Burn> burns = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        for (JsonNode node : burnsNode) {
            try {
                Transaction tx = parseTransaction(chainId, node.get("transaction"));
                transactions.add(tx);
                burns.add(parseBurn(chainId, node, tx.getId()));
            } catch (Exception e) {
                log.error("Failed to parse burn: {}", node, e);
            }
        }

        if (!transactions.isEmpty()) {
            transactionRepository.saveAll(transactions);
        }
        if (!burns.isEmpty()) {
            burnRepository.saveAll(burns);
            log.info("Saved {} burns for chain: {}", burns.size(), chainId);
        }
    }

    private Transaction parseTransaction(String chainId, JsonNode node) {
        Transaction tx = new Transaction();
        tx.setId(node.get("id").asText().toLowerCase());
        tx.setChainId(chainId);
        tx.setBlockNumber(parseLong(node, "blockNumber"));
        tx.setTimestamp(parseLong(node, "timestamp"));
        return tx;
    }

    private Burn parseBurn(String chainId, JsonNode node, String transactionId) {
        Burn burn = new Burn();
        burn.setId(node.get("id").asText());
        burn.setChainId(chainId);
        burn.setTransactionId(transactionId);
        burn.setTimestamp(parseLong(node, "timestamp"));
        burn.setPairId(node.get("pair").get("id").asText().toLowerCase());
        burn.setSender(node.hasNonNull("sender") ? node.get("sender").asText().toLowerCase() : null);
        burn.setLiquidity(parseBigDecimal(node, "liquidity"));
        burn.setAmount0(node.hasNonNull("amount0") ? parseBigDecimal(node, "amount0") : null);
        burn.setAmount1(node.hasNonNull("amount1") ? parseBigDecimal(node, "amount1") : null);
        burn.setToAddress(node.hasNonNull("to") ? node.get("to").asText().toLowerCase() : null);
        burn.setLogIndex(node.hasNonNull("logIndex") ? node.get("logIndex").asLong() : null);
        burn.setAmountUsd(node.hasNonNull("amountUSD") ? parseBigDecimal(node, "amountUSD") : null);
        burn.setFeeTo(node.hasNonNull("feeTo") ? node.get("feeTo").asText().toLowerCase() : null);
        burn.setFeeLiquidity(node.hasNonNull("feeLiquidity") ? parseBigDecimal(node, "feeLiquidity") : null);
        burn.setNeedsComplete(node.hasNonNull("needsComplete") ? node.get("needsComplete").asBoolean() : true);
        return burn;
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
