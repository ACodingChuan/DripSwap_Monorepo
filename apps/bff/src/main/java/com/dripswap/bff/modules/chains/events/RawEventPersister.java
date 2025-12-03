package com.dripswap.bff.modules.chains.events;

import com.dripswap.bff.modules.chains.model.RawEvent;
import com.dripswap.bff.repository.RawEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Persists raw blockchain events to the database.
 * Implements idempotency check based on txHash + logIndex.
 */
@Component
public class RawEventPersister {

    private static final Logger logger = LoggerFactory.getLogger(RawEventPersister.class);
    private final RawEventRepository rawEventRepository;
    private final Tracer tracer;

    public RawEventPersister(RawEventRepository rawEventRepository, Tracer tracer) {
        this.rawEventRepository = rawEventRepository;
        this.tracer = tracer;
    }

    /**
     * Persist a RawEvent to database with duplicate check.
     * Returns true if saved, false if already exists.
     *
     * @param rawEvent RawEvent object to persist
     * @return true if newly saved, false if duplicate
     */
    public boolean persistEvent(RawEvent rawEvent) {
        Span span = tracer.spanBuilder("RawEventPersister.persistEvent")
                .setAttribute("tx.hash", rawEvent.getTxHash())
                .setAttribute("log.index", rawEvent.getLogIndex())
                .setAttribute("chain.id", rawEvent.getChainId())
                .startSpan();
        try {
            // Check for duplicate based on txHash + logIndex
            if (isDuplicate(rawEvent)) {
                logger.debug("Event already exists: txHash={}, logIndex={}, skipping",
                        rawEvent.getTxHash(), rawEvent.getLogIndex());
                return false;
            }

            // Save new event
            RawEvent saved = rawEventRepository.save(rawEvent);
            logger.info("Persisted raw event: id={}, chainId={}, blockNum={}, txHash={}",
                    saved.getId(), rawEvent.getChainId(), rawEvent.getBlockNumber(),
                    rawEvent.getTxHash());

            return true;
        } catch (Exception e) {
            logger.error("Failed to persist event: txHash={}, logIndex={}, error={}",
                    rawEvent.getTxHash(), rawEvent.getLogIndex(), e.getMessage(), e);
            span.recordException(e);
            return false;
        } finally {
            span.end();
        }
    }

    /**
     * Check if event with same txHash and logIndex already exists.
     */
    private boolean isDuplicate(RawEvent rawEvent) {
        Optional<RawEvent> existing = rawEventRepository.findByTxHashAndLogIndex(
                rawEvent.getTxHash(),
                rawEvent.getLogIndex()
        );
        return existing.isPresent();
    }

    /**
     * Batch persist multiple events.
     *
     * @param rawEvents List of RawEvent objects
     * @return Number of successfully saved events
     */
    public int persistBatch(List<RawEvent> rawEvents) {
        Span span = tracer.spanBuilder("RawEventPersister.persistBatch")
                .setAttribute("event.count", rawEvents != null ? rawEvents.size() : 0)
                .startSpan();
        try {
            if (rawEvents == null || rawEvents.isEmpty()) {
                return 0;
            }

            int saved = 0;
            for (RawEvent event : rawEvents) {
                if (persistEvent(event)) {
                    saved++;
                }
            }

            logger.info("Batch persist completed: {} / {} events saved",
                    saved, rawEvents.size());

            return saved;
        } finally {
            span.end();
        }
    }
}
