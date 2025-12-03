package com.dripswap.bff.modules.chains.events;

import com.dripswap.bff.modules.chains.model.RawEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventValues;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes Web3j Log events into RawEvent domain objects.
 * Extracts chainId, blockNumber, txHash, logIndex, eventSig, and rawData.
 */
@Component
public class EventDecoder {

    private static final Logger logger = LoggerFactory.getLogger(EventDecoder.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Decode Web3j Log into RawEvent.
     *
     * @param logEvent Web3j Log object
     * @param chainId  Blockchain chain ID
     * @return RawEvent object
     */
    public RawEvent decodeLog(Log logEvent, String chainId) {
        try {
            RawEvent rawEvent = new RawEvent();
            rawEvent.setChainId(chainId);
            rawEvent.setBlockNumber(logEvent.getBlockNumber().longValue());
            rawEvent.setTxHash(logEvent.getTransactionHash());
            rawEvent.setLogIndex(logEvent.getLogIndex().intValue());

            // Extract event signature (first topic)
            String eventSig = null;
            if (logEvent.getTopics() != null && !logEvent.getTopics().isEmpty()) {
                eventSig = logEvent.getTopics().get(0);
            }
            rawEvent.setEventSig(eventSig);

            // Serialize entire log as JSON
            String rawData = serializeLogToJson(logEvent);
            rawEvent.setRawData(rawData);

            rawEvent.setCreatedAt(LocalDateTime.now());

            logger.debug("Decoded event: chainId={}, blockNum={}, txHash={}, logIndex={}",
                    chainId, logEvent.getBlockNumber(), logEvent.getTransactionHash(), logEvent.getLogIndex());

            return rawEvent;
        } catch (Exception e) {
            logger.error("Failed to decode log: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decode log", e);
        }
    }

    /**
     * Decode Web3j EventValues into RawEvent.
     * (Alternative method if using EventValues from contract filters)
     *
     * @param eventValues Web3j EventValues
     * @param chainId     Blockchain chain ID
     * @param blockNumber Block number
     * @param txHash      Transaction hash
     * @param logIndex    Log index
     * @return RawEvent object
     */
    public RawEvent decodeEventValues(EventValues eventValues, String chainId, BigInteger blockNumber,
                                      String txHash, BigInteger logIndex) {
        try {
            RawEvent rawEvent = new RawEvent();
            rawEvent.setChainId(chainId);
            rawEvent.setBlockNumber(blockNumber.longValue());
            rawEvent.setTxHash(txHash);
            rawEvent.setLogIndex(logIndex.intValue());

            // Build a simple raw data representation
            Map<String, Object> rawDataMap = new HashMap<>();
            rawDataMap.put("indexed", eventValues.getIndexedValues());
            rawDataMap.put("non_indexed", eventValues.getNonIndexedValues());
            String rawData = objectMapper.writeValueAsString(rawDataMap);
            rawEvent.setRawData(rawData);

            rawEvent.setCreatedAt(LocalDateTime.now());

            return rawEvent;
        } catch (Exception e) {
            logger.error("Failed to decode EventValues: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decode EventValues", e);
        }
    }

    /**
     * Serialize Web3j Log to JSON string.
     */
    private String serializeLogToJson(Log logEvent) throws Exception {
        Map<String, Object> logMap = new HashMap<>();

        logMap.put("address", logEvent.getAddress());
        logMap.put("topics", logEvent.getTopics());
        logMap.put("data", logEvent.getData());
        logMap.put("blockNumber", logEvent.getBlockNumber());
        logMap.put("transactionHash", logEvent.getTransactionHash());
        logMap.put("transactionIndex", logEvent.getTransactionIndex());
        logMap.put("blockHash", logEvent.getBlockHash());
        logMap.put("logIndex", logEvent.getLogIndex());
        logMap.put("removed", logEvent.isRemoved());

        return objectMapper.writeValueAsString(logMap);
    }

    /**
     * Extract event signature from topics.
     */
    public String extractEventSignature(List<String> topics) {
        if (topics != null && !topics.isEmpty()) {
            return topics.get(0);
        }
        return null;
    }

    /**
     * Extract indexed parameters from topics.
     */
    public List<String> extractIndexedParams(List<String> topics) {
        if (topics != null && topics.size() > 1) {
            return topics.subList(1, topics.size());
        }
        return List.of();
    }
}
