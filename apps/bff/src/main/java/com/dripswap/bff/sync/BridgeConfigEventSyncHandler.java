package com.dripswap.bff.sync;

import com.dripswap.bff.entity.BridgeConfigEvent;
import com.dripswap.bff.repository.BridgeConfigEventRepository;
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
public class BridgeConfigEventSyncHandler {

    private final BridgeConfigEventRepository bridgeConfigEventRepository;

    @Transactional
    public void handleBridgeConfigEvents(String chainId, JsonNode eventsNode) {
        if (eventsNode == null || !eventsNode.isArray()) {
            return;
        }

        List<BridgeConfigEvent> events = new ArrayList<>();
        for (JsonNode node : eventsNode) {
            try {
                events.add(parseEvent(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse bridgeConfigEvent: {}", node, e);
            }
        }

        if (!events.isEmpty()) {
            bridgeConfigEventRepository.saveAll(events);
            log.info("Saved {} bridgeConfigEvents for chain: {}", events.size(), chainId);
        }
    }

    private BridgeConfigEvent parseEvent(String chainId, JsonNode node) {
        BridgeConfigEvent event = new BridgeConfigEvent();
        event.setId(node.get("id").asText());
        event.setChainId(chainId);
        event.setEventName(node.get("eventName").asText());
        event.setToken(node.hasNonNull("token") ? node.get("token").asText().toLowerCase() : null);
        event.setPool(node.hasNonNull("pool") ? node.get("pool").asText().toLowerCase() : null);
        event.setMinAmount(node.hasNonNull("minAmount") ? parseBigDecimal(node, "minAmount") : null);
        event.setMaxAmount(node.hasNonNull("maxAmount") ? parseBigDecimal(node, "maxAmount") : null);
        event.setNativeAllowed(node.hasNonNull("nativeAllowed") ? node.get("nativeAllowed").asBoolean() : null);
        event.setLinkAllowed(node.hasNonNull("linkAllowed") ? node.get("linkAllowed").asBoolean() : null);
        event.setNewFee(node.hasNonNull("newFee") ? parseBigDecimal(node, "newFee") : null);
        event.setNewCollector(node.hasNonNull("newCollector") ? node.get("newCollector").asText().toLowerCase() : null);
        event.setBlockNumber(parseLong(node, "blockNumber"));
        event.setTimestamp(parseLong(node, "timestamp"));
        event.setTransactionHash(node.get("transactionHash").asText().toLowerCase());
        return event;
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

