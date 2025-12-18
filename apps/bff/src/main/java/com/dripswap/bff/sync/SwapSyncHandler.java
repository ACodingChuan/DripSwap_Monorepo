package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Swap;
import com.dripswap.bff.entity.Transaction;
import com.dripswap.bff.repository.SwapRepository;
import com.dripswap.bff.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Swap 数据同步处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapSyncHandler {

    private final SwapRepository swapRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void handleSwaps(String chainId, JsonNode swapsNode) {
        if (swapsNode == null || !swapsNode.isArray()) {
            return;
        }

        List<Swap> swaps = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        for (JsonNode node : swapsNode) {
            try {
                Transaction tx = parseTransaction(chainId, node.get("transaction"));
                transactions.add(tx);
                swaps.add(parseSwap(chainId, node, tx.getId()));
            } catch (Exception e) {
                log.error("Failed to parse swap: {}", node, e);
            }
        }

        if (!transactions.isEmpty()) {
            transactionRepository.saveAll(transactions);
        }
        if (!swaps.isEmpty()) {
            swapRepository.saveAll(swaps);
            log.info("Saved {} swaps for chain: {}", swaps.size(), chainId);
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

    private Swap parseSwap(String chainId, JsonNode node, String transactionId) {
        Swap swap = new Swap();
        swap.setId(node.get("id").asText());
        swap.setChainId(chainId);
        swap.setTransactionId(transactionId);
        swap.setTimestamp(parseLong(node, "timestamp"));
        swap.setPairId(node.get("pair").get("id").asText().toLowerCase());
        swap.setSender(node.get("sender").asText().toLowerCase());
        swap.setFromAddress(node.get("from").asText().toLowerCase());
        swap.setToAddress(node.get("to").asText().toLowerCase());
        swap.setAmount0In(parseBigDecimal(node, "amount0In"));
        swap.setAmount1In(parseBigDecimal(node, "amount1In"));
        swap.setAmount0Out(parseBigDecimal(node, "amount0Out"));
        swap.setAmount1Out(parseBigDecimal(node, "amount1Out"));
        swap.setLogIndex(node.hasNonNull("logIndex") ? node.get("logIndex").asLong() : null);
        swap.setAmountUsd(parseBigDecimal(node, "amountUSD"));
        return swap;
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
