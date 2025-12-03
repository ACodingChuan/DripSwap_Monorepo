package com.dripswap.bff.modules.chains.events;

import com.dripswap.bff.modules.chains.model.RawEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens to blockchain events from multiple chains via WebSocket.
 * Decodes logs and persists them to the database.
 */
@Component
public class ChainEventListener {

    private static final Logger logger = LoggerFactory.getLogger(ChainEventListener.class);

    private final WsConnectionManager wsConnectionManager;
    private final EventDecoder eventDecoder;
    private final RawEventPersister rawEventPersister;
    private final Tracer tracer;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    public ChainEventListener(WsConnectionManager wsConnectionManager,
                            EventDecoder eventDecoder,
                            RawEventPersister rawEventPersister,
                            Tracer tracer) {
        this.wsConnectionManager = wsConnectionManager;
        this.eventDecoder = eventDecoder;
        this.rawEventPersister = rawEventPersister;
        this.tracer = tracer;
    }

    /**
     * Start listening to events from all chains after application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        Span span = tracer.spanBuilder("ChainEventListener.startListening").startSpan();
        try {
            logger.info("Starting blockchain event listeners");

            // Initialize WebSocket connections
            wsConnectionManager.initializeConnections();

            // Get all Web3j instances
            Map<String, Web3j> web3jInstances = wsConnectionManager.getAllWeb3jInstances();

            if (web3jInstances.isEmpty()) {
                logger.warn("No WebSocket connections available");
                return;
            }

            // Start listening for each chain
            for (Map.Entry<String, Web3j> entry : web3jInstances.entrySet()) {
                String chainId = entry.getKey();
                Web3j web3j = entry.getValue();

                executorService.submit(() -> listenToChain(chainId, web3j));
            }

            logger.info("Started listening to {} chain(s)", web3jInstances.size());
        } finally {
            span.end();
        }
    }

    /**
     * Listen to all events from a specific chain.
     */
    private void listenToChain(String chainId, Web3j web3j) {
        Span span = tracer.spanBuilder("ChainEventListener.listenToChain")
                .setAttribute("chain.id", chainId)
                .startSpan();
        try {
            logger.info("Setting up event listener for chain: {}", chainId);

            // Subscribe to all logs (no filter = all events from all contracts)
            web3j.ethLogFlowable(new EthFilter())
                    .subscribe(
                            logEvent -> handleLog(chainId, logEvent),
                            error -> handleError(chainId, error),
                            () -> logger.info("Event stream completed for chain: {}", chainId)
                    );
        } catch (Exception e) {
            logger.error("Error setting up listener for chain {}: {}", chainId, e.getMessage(), e);
            span.recordException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Handle incoming log event.
     */
    private void handleLog(String chainId, Log logEvent) {
        Span span = tracer.spanBuilder("ChainEventListener.handleLog")
                .setAttribute("chain.id", chainId)
                .setAttribute("tx.hash", logEvent != null ? logEvent.getTransactionHash() : "null")
                .startSpan();
        try {
            if (logEvent == null) {
                return;
            }

            // Log details
            logger.debug("Received event from chain {}: block={}, tx={}, logIndex={}",
                    chainId, logEvent.getBlockNumber(), logEvent.getTransactionHash(),
                    logEvent.getLogIndex());

            // Decode log to RawEvent
            RawEvent rawEvent = eventDecoder.decodeLog(logEvent, chainId);

            // Persist to database
            boolean saved = rawEventPersister.persistEvent(rawEvent);

            if (saved) {
                logger.info("Event persisted: chain={}, block={}, tx={}, eventSig={}",
                        chainId, rawEvent.getBlockNumber(), rawEvent.getTxHash(),
                        rawEvent.getEventSig());
            }
        } catch (Exception e) {
            logger.error("Error handling log event from chain {}: {}", chainId, e.getMessage(), e);
            span.recordException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Handle subscription error.
     */
    private void handleError(String chainId, Throwable error) {
        logger.error("Error in event stream for chain {}: {}", chainId, error.getMessage(), error);

        // Attempt to reconnect
        try {
            Thread.sleep(5000); // Wait 5 seconds before reconnecting
            wsConnectionManager.reconnectChain(chainId);
            
            // Resume listening
            wsConnectionManager.getWeb3j(chainId).ifPresent(web3j ->
                    executorService.submit(() -> listenToChain(chainId, web3j))
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while reconnecting chain {}", chainId);
        }
    }

    /**
     * Stop all listeners and close connections.
     */
    public void stopListening() {
        logger.info("Stopping blockchain event listeners");
        executorService.shutdown();
        wsConnectionManager.closeAll();
        logger.info("Event listeners stopped");
    }
}
