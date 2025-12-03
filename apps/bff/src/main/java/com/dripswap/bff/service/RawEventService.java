package com.dripswap.bff.service;

import com.dripswap.bff.modules.chains.model.RawEvent;
import com.dripswap.bff.repository.RawEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Raw event service for querying and analyzing raw blockchain events.
 */
@Service
public class RawEventService {

    private static final Logger logger = LoggerFactory.getLogger(RawEventService.class);

    private final RawEventRepository rawEventRepository;
    private final Tracer tracer;

    public RawEventService(RawEventRepository rawEventRepository, Tracer tracer) {
        this.rawEventRepository = rawEventRepository;
        this.tracer = tracer;
    }

    /**
     * Get recent raw events with pagination.
     */
    public Page<RawEvent> getRecentEvents(int page, int size) {
        Span span = tracer.spanBuilder("RawEventService.getRecentEvents")
                .setAttribute("page", page)
                .setAttribute("size", size)
                .startSpan();
        try {
            Pageable pageable = PageRequest.of(page, size);
            return rawEventRepository.findAll(pageable);
        } catch (Exception e) {
            logger.error("Error fetching recent events: {}", e.getMessage(), e);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Get events by chain ID.
     */
    public List<RawEvent> getEventsByChain(String chainId) {
        Span span = tracer.spanBuilder("RawEventService.getEventsByChain")
                .setAttribute("chain.id", chainId)
                .startSpan();
        try {
            return rawEventRepository.findByChainId(chainId);
        } catch (Exception e) {
            logger.error("Error fetching events for chain {}: {}", chainId, e.getMessage(), e);
            span.recordException(e);
            return List.of();
        } finally {
            span.end();
        }
    }

    /**
     * Get event count by chain.
     */
    public long getEventCountByChain(String chainId) {
        Span span = tracer.spanBuilder("RawEventService.getEventCountByChain")
                .setAttribute("chain.id", chainId)
                .startSpan();
        try {
            List<RawEvent> events = rawEventRepository.findByChainId(chainId);
            return events.size();
        } catch (Exception e) {
            logger.error("Error counting events for chain {}: {}", chainId, e.getMessage(), e);
            span.recordException(e);
            return 0;
        } finally {
            span.end();
        }
    }

    /**
     * Get events from last N hours.
     */
    public List<RawEvent> getEventsFromLastHours(int hours) {
        Span span = tracer.spanBuilder("RawEventService.getEventsFromLastHours")
                .setAttribute("hours", hours)
                .startSpan();
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(hours, ChronoUnit.HOURS);
            List<RawEvent> allEvents = rawEventRepository.findAll();
            return allEvents.stream()
                    .filter(e -> e.getCreatedAt() != null && e.getCreatedAt().isAfter(cutoff))
                    .toList();
        } catch (Exception e) {
            logger.error("Error fetching events from last {} hours: {}", hours, e.getMessage(), e);
            span.recordException(e);
            return List.of();
        } finally {
            span.end();
        }
    }

    /**
     * Get single event by ID.
     */
    public Optional<RawEvent> getEventById(Long id) {
        Span span = tracer.spanBuilder("RawEventService.getEventById")
                .setAttribute("event.id", id)
                .startSpan();
        try {
            return rawEventRepository.findById(id);
        } catch (Exception e) {
            logger.error("Error fetching event by ID {}: {}", id, e.getMessage(), e);
            span.recordException(e);
            return Optional.empty();
        } finally {
            span.end();
        }
    }
}

