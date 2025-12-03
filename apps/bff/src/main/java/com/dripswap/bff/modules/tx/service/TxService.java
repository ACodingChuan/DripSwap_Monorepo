package com.dripswap.bff.modules.tx.service;

import com.dripswap.bff.modules.chains.model.RawEvent;
import com.dripswap.bff.modules.tx.model.TxRecord;
import com.dripswap.bff.modules.tx.repository.TxRepository;
import com.dripswap.bff.repository.RawEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Transaction parsing service.
 * Scans raw_events table and generates structured tx_records.
 * Runs as scheduled task every 5 seconds.
 */
@Service
@Transactional
public class TxService {

    private static final Logger logger = LoggerFactory.getLogger(TxService.class);

    private final RawEventRepository rawEventRepository;
    private final TxRepository txRepository;
    private final Tracer tracer;

    public TxService(RawEventRepository rawEventRepository, TxRepository txRepository, Tracer tracer) {
        this.rawEventRepository = rawEventRepository;
        this.txRepository = txRepository;
        this.tracer = tracer;
    }

    /**
     * Scheduled task: Process raw events every 5 seconds.
     * Scans raw_events that haven't been converted to tx_records yet.
     */
    @Scheduled(cron = "*/5 * * * * *")
    public void processRawEvents() {
        Span span = tracer.spanBuilder("TxService.processRawEvents").startSpan();
        try {
            logger.debug("Starting raw events processing");

            // Get all raw events
            // In production, you would filter for unprocessed events
            List<RawEvent> allRawEvents = rawEventRepository.findAll();

            if (allRawEvents.isEmpty()) {
                logger.debug("No raw events to process");
                return;
            }

            // Group by chain_id and tx_hash
            Map<String, Map<String, List<RawEvent>>> groupedByChainAndTx = groupRawEventsByChainAndTx(allRawEvents);

            int processedCount = 0;
            int skippedCount = 0;

            // Process each tx_hash in each chain
            for (Map.Entry<String, Map<String, List<RawEvent>>> chainEntry : groupedByChainAndTx.entrySet()) {
                String chainId = chainEntry.getKey();
                Map<String, List<RawEvent>> txHashMap = chainEntry.getValue();

                for (Map.Entry<String, List<RawEvent>> txEntry : txHashMap.entrySet()) {
                    String txHash = txEntry.getKey();
                    List<RawEvent> eventsForTx = txEntry.getValue();

                    // Check if this tx_hash has already been processed
                    Optional<TxRecord> existingTxRecord = txRepository.findByChainIdAndTxHash(chainId, txHash);
                    if (existingTxRecord.isPresent()) {
                        skippedCount++;
                        continue;
                    }

                    // Process this transaction (create one TxRecord per tx_hash)
                    boolean processed = processSingleTransaction(chainId, txHash, eventsForTx);
                    if (processed) {
                        processedCount++;
                    }
                }
            }

            if (processedCount > 0 || skippedCount > 0) {
                logger.info("Processed {} raw event groups, skipped {} duplicates",
                        processedCount, skippedCount);
            }
        } catch (Exception e) {
            logger.error("Error processing raw events: {}", e.getMessage(), e);
            span.recordException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Process a single transaction and create TxRecord.
     * Takes the first event's metadata and uses decoded_name from event_sig mapping.
     *
     * @param chainId     Chain identifier
     * @param txHash      Transaction hash
     * @param rawEvents   List of raw events for this tx
     * @return true if successfully processed, false otherwise
     */
    private boolean processSingleTransaction(String chainId, String txHash, List<RawEvent> rawEvents) {
        Span span = tracer.spanBuilder("TxService.processSingleTransaction")
                .setAttribute("chain.id", chainId)
                .setAttribute("tx.hash", txHash)
                .setAttribute("event.count", rawEvents != null ? rawEvents.size() : 0)
                .startSpan();
        try {
            if (rawEvents == null || rawEvents.isEmpty()) {
                return false;
            }

            // Use the first event as the primary event for this transaction
            RawEvent primaryEvent = rawEvents.get(0);

            // Extract transaction details
            Long blockNumber = primaryEvent.getBlockNumber();
            String eventSig = primaryEvent.getEventSig();
            String rawData = primaryEvent.getRawData();

            // Map event_sig to decoded_name
            String decodedName = mapEventSigToDecodedName(eventSig);

            // Determine status based on decoded_name
            String status = determineStatus(decodedName);

            // Create and save TxRecord
            TxRecord txRecord = new TxRecord();
            txRecord.setChainId(chainId);
            txRecord.setBlockNumber(blockNumber);
            txRecord.setTxHash(txHash);
            txRecord.setEventSig(eventSig);
            txRecord.setDecodedName(decodedName);
            txRecord.setDecodedData(rawData);
            txRecord.setStatus(status);
            txRecord.setCreatedAt(LocalDateTime.now());

            TxRecord savedRecord = txRepository.save(txRecord);

            logger.info("Processed transaction: chainId={}, txHash={}, eventCount={}, decodedName={}, status={}",
                    chainId, txHash, rawEvents.size(), decodedName, status);

            return true;
        } catch (Exception e) {
            logger.error("Error processing transaction: chainId={}, txHash={}, error={}",
                    chainId, txHash, e.getMessage(), e);
            span.recordException(e);
            return false;
        } finally {
            span.end();
        }
    }

    /**
     * Group raw events by chain_id and tx_hash.
     * Returns nested map: chainId -> txHash -> List<RawEvent>
     */
    private Map<String, Map<String, List<RawEvent>>> groupRawEventsByChainAndTx(List<RawEvent> rawEvents) {
        Map<String, Map<String, List<RawEvent>>> result = new HashMap<>();

        for (RawEvent event : rawEvents) {
            String chainId = event.getChainId();
            String txHash = event.getTxHash();

            result.computeIfAbsent(chainId, k -> new HashMap<>())
                    .computeIfAbsent(txHash, k -> new ArrayList<>())
                    .add(event);
        }

        return result;
    }

    /**
     * Map event signature to human-readable decoded name.
     * Simple mapping based on event_sig pattern.
     *
     * @param eventSig Event signature from raw event
     * @return Decoded name (e.g., "Transfer", "Swap", "unknown")
     */
    private String mapEventSigToDecodedName(String eventSig) {
        if (eventSig == null || eventSig.isEmpty()) {
            return "unknown";
        }

        // Check for common ERC20 and DEX event signatures
        // Format: 0x<hash>

        // Transfer event: 0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef
        if (eventSig.equalsIgnoreCase("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")) {
            return "Transfer";
        }

        // Approval event: 0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925
        if (eventSig.equalsIgnoreCase("0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925")) {
            return "Approval";
        }

        // Swap event: 0xd78ad95fa46ab8d7b872519e710f362c7dea70131084f770ccee07fc7a1d580f
        if (eventSig.equalsIgnoreCase("0xd78ad95fa46ab8d7b872519e710f362c7dea70131084f770ccee07fc7a1d580f")) {
            return "Swap";
        }

        // Mint event: 0x0d3648bd0f6ba80134a33ba9275ac585d9d315f0ad8355cddefde31afa28d0e9
        if (eventSig.equalsIgnoreCase("0x0d3648bd0f6ba80134a33ba9275ac585d9d315f0ad8355cddefde31afa28d0e9")) {
            return "Mint";
        }

        // Burn event: 0xdccd412f0b936dcbe72d3b16885e867d6d3c193faf3c47cda2137a41e4ed294f
        if (eventSig.equalsIgnoreCase("0xdccd412f0b936dcbe72d3b16885e867d6d3c193faf3c47cda2137a41e4ed294f")) {
            return "Burn";
        }

        // Default: unknown
        return "unknown";
    }

    /**
     * Determine transaction status based on decoded name.
     *
     * @param decodedName Decoded event name
     * @return Status: "completed", "swap", or "unknown"
     */
    private String determineStatus(String decodedName) {
        if (decodedName == null) {
            return "unknown";
        }

        return switch (decodedName) {
            case "Transfer", "Approval" -> "completed";
            case "Swap" -> "swap";
            case "Mint", "Burn" -> "completed";
            default -> "unknown";
        };
    }

    /**
     * Get transaction records by chain and status.
     */
    public List<TxRecord> getTxRecordsByChainAndStatus(String chainId, String status) {
        return txRepository.findByChainIdAndStatus(chainId, status);
    }

    /**
     * Get all transaction records by chain.
     */
    public List<TxRecord> getTxRecordsByChain(String chainId) {
        return txRepository.findByChainId(chainId);
    }

    /**
     * Get transaction record by tx_hash.
     */
    public Optional<TxRecord> getTxRecordByHash(String txHash) {
        return txRepository.findByTxHash(txHash);
    }

    /**
     * Get all transaction records by decoded name (event type).
     */
    public List<TxRecord> getTxRecordsByDecodedName(String decodedName) {
        return txRepository.findByDecodedName(decodedName);
    }

    /**
     * Count pending transactions.
     */
    public long countPendingTransactions() {
        return txRepository.countByStatus("pending");
    }

    /**
     * Count completed transactions.
     */
    public long countCompletedTransactions() {
        return txRepository.countByStatus("completed");
    }

    /**
     * Get transaction statistics by chain.
     */
    public Map<String, Object> getTransactionStats(String chainId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("chainId", chainId);
        stats.put("completed", txRepository.countByChainIdAndStatus(chainId, "completed"));
        stats.put("swap", txRepository.countByChainIdAndStatus(chainId, "swap"));
        stats.put("unknown", txRepository.countByChainIdAndStatus(chainId, "unknown"));
        stats.put("pending", txRepository.countByChainIdAndStatus(chainId, "pending"));
        return stats;
    }
}
