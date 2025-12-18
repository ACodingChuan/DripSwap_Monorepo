package com.dripswap.bff.sync;

import com.dripswap.bff.entity.BridgeTransfer;
import com.dripswap.bff.repository.BridgeTransferRepository;
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
public class BridgeTransferSyncHandler {

    private final BridgeTransferRepository bridgeTransferRepository;

    @Transactional
    public void handleBridgeTransfers(String chainId, JsonNode transfersNode) {
        if (transfersNode == null || !transfersNode.isArray()) {
            return;
        }

        List<BridgeTransfer> transfers = new ArrayList<>();
        for (JsonNode node : transfersNode) {
            try {
                transfers.add(parseTransfer(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse bridgeTransfer: {}", node, e);
            }
        }

        if (!transfers.isEmpty()) {
            bridgeTransferRepository.saveAll(transfers);
            log.info("Saved {} bridgeTransfers for chain: {}", transfers.size(), chainId);
        }
    }

    private BridgeTransfer parseTransfer(String chainId, JsonNode node) {
        BridgeTransfer transfer = new BridgeTransfer();
        transfer.setId(node.get("id").asText());
        transfer.setChainId(chainId);
        transfer.setTxHash(node.get("txHash").asText().toLowerCase());
        transfer.setBlockNumber(parseLong(node, "blockNumber"));
        transfer.setTimestamp(parseLong(node, "timestamp"));
        transfer.setMessageId(node.get("messageId").asText());
        transfer.setSender(node.get("sender").asText().toLowerCase());
        transfer.setToken(node.get("token").asText().toLowerCase());
        transfer.setPool(node.get("pool").asText().toLowerCase());
        transfer.setAmount(parseBigDecimal(node, "amount"));
        transfer.setDstSelector(parseLong(node, "dstSelector"));
        transfer.setReceiverChainName(node.get("receiverChainName").asText());
        transfer.setReceiver(node.get("receiver").asText().toLowerCase());
        transfer.setPayInLink(node.get("payInLink").asBoolean());
        transfer.setCcipFee(parseBigDecimal(node, "ccipFee"));
        transfer.setServiceFeePaid(parseBigDecimal(node, "serviceFeePaid"));
        return transfer;
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

