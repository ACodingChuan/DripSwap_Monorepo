package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Mint;
import com.dripswap.bff.entity.Transaction;
import com.dripswap.bff.repository.MintRepository;
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
public class MintSyncHandler {

    private final MintRepository mintRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void handleMints(String chainId, JsonNode mintsNode) {
        if (mintsNode == null || !mintsNode.isArray()) {
            return;
        }

        List<Mint> mints = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        for (JsonNode node : mintsNode) {
            try {
                Transaction tx = parseTransaction(chainId, node.get("transaction"));
                transactions.add(tx);
                mints.add(parseMint(chainId, node, tx.getId()));
            } catch (Exception e) {
                log.error("Failed to parse mint: {}", node, e);
            }
        }

        if (!transactions.isEmpty()) {
            transactionRepository.saveAll(transactions);
        }
        if (!mints.isEmpty()) {
            mintRepository.saveAll(mints);
            log.info("Saved {} mints for chain: {}", mints.size(), chainId);
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

    private Mint parseMint(String chainId, JsonNode node, String transactionId) {
        Mint mint = new Mint();
        mint.setId(node.get("id").asText());
        mint.setChainId(chainId);
        mint.setTransactionId(transactionId);
        mint.setTimestamp(parseLong(node, "timestamp"));
        mint.setPairId(node.get("pair").get("id").asText().toLowerCase());
        mint.setToAddress(node.get("to").asText().toLowerCase());
        mint.setLiquidity(parseBigDecimal(node, "liquidity"));
        mint.setSender(node.hasNonNull("sender") ? node.get("sender").asText().toLowerCase() : null);
        mint.setAmount0(node.hasNonNull("amount0") ? parseBigDecimal(node, "amount0") : null);
        mint.setAmount1(node.hasNonNull("amount1") ? parseBigDecimal(node, "amount1") : null);
        mint.setLogIndex(node.hasNonNull("logIndex") ? node.get("logIndex").asLong() : null);
        mint.setAmountUsd(node.hasNonNull("amountUSD") ? parseBigDecimal(node, "amountUSD") : null);
        mint.setFeeTo(node.hasNonNull("feeTo") ? node.get("feeTo").asText().toLowerCase() : null);
        mint.setFeeLiquidity(node.hasNonNull("feeLiquidity") ? parseBigDecimal(node, "feeLiquidity") : null);
        return mint;
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
