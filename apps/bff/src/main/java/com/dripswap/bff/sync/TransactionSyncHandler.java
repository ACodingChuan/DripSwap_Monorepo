package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Transaction;
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
public class TransactionSyncHandler {

    private final TransactionRepository transactionRepository;

    @Transactional
    public void handleTransactions(String chainId, JsonNode transactionsNode) {
        if (transactionsNode == null || !transactionsNode.isArray()) {
            return;
        }

        List<Transaction> txs = new ArrayList<>();
        for (JsonNode node : transactionsNode) {
            try {
                txs.add(parseTx(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse transaction: {}", node, e);
            }
        }

        if (!txs.isEmpty()) {
            transactionRepository.saveAll(txs);
            log.info("Saved {} transactions for chain: {}", txs.size(), chainId);
        }
    }

    private Transaction parseTx(String chainId, JsonNode node) {
        Transaction tx = new Transaction();
        tx.setId(node.get("id").asText().toLowerCase());
        tx.setChainId(chainId);
        tx.setBlockNumber(parseLong(node, "blockNumber"));
        tx.setTimestamp(parseLong(node, "timestamp"));
        return tx;
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

