package com.dripswap.bff.modules.analytics.service;

import com.dripswap.bff.modules.analytics.dto.*;
import com.dripswap.bff.modules.chains.model.RawEvent;
import com.dripswap.bff.modules.gql.model.TokenPayload;
import com.dripswap.bff.modules.gql.model.PairPayload;
import com.dripswap.bff.modules.tx.model.TxRecord;
import com.dripswap.bff.modules.tx.repository.TxRepository;
import com.dripswap.bff.modules.subgraph.SubgraphClient;
import com.dripswap.bff.repository.RawEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics service for computing blockchain statistics.
 * Aggregates data from raw_events, tx_records, and subgraph.
 */
@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);

    private static final BigDecimal UNISWAP_V2_FEE_RATE = new BigDecimal("0.003"); // 0.3%
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private final RawEventRepository rawEventRepository;
    private final TxRepository txRepository;
    private final SubgraphClient subgraphClient;
    private final Tracer tracer;

    public AnalyticsService(RawEventRepository rawEventRepository,
                           TxRepository txRepository,
                           SubgraphClient subgraphClient,
                           Tracer tracer) {
        this.rawEventRepository = rawEventRepository;
        this.txRepository = txRepository;
        this.subgraphClient = subgraphClient;
        this.tracer = tracer;
    }

    /**
     * Get token statistics including volume and liquidity.
     *
     * @param tokenAddress Token address
     * @return TokenStats DTO
     */
    public TokenStats getTokenStats(String tokenAddress) {
        Span span = tracer.spanBuilder("AnalyticsService.getTokenStats")
                .setAttribute("token.address", tokenAddress)
                .startSpan();
        try {
            logger.debug("Computing statistics for token: {}", tokenAddress);

            TokenStats stats = new TokenStats();
            stats.setToken(tokenAddress);

            // Get volume from tx_records
            List<TxRecord> allTxRecords = txRepository.findAll();
            BigDecimal volume24h = calculateVolumeFromTxRecords(allTxRecords, 1);
            BigDecimal volume7d = calculateVolumeFromTxRecords(allTxRecords, 7);

            stats.setVolume24h(volume24h);
            stats.setVolume7d(volume7d);

            // Get liquidity from subgraph (aggregate pair reserves)
            BigDecimal liquidityUSD = calculateTokenLiquidity(tokenAddress);
            stats.setLiquidityUSD(liquidityUSD);

            // Count unique holders from raw events (Transfer events)
            Integer holders = countTokenHolders(tokenAddress);
            stats.setHolders(holders);

            logger.info("Token stats computed: token={}, volume24h={}, volume7d={}, liquidity={}, holders={}",
                    tokenAddress, volume24h, volume7d, liquidityUSD, holders);

            return stats;
        } catch (Exception e) {
            logger.error("Error computing token stats: {}", e.getMessage(), e);
            span.recordException(e);
            return createEmptyTokenStats(tokenAddress);
        } finally {
            span.end();
        }
    }

    /**
     * Get pair statistics including price, reserves, and APR.
     *
     * @param pairAddress Pair address
     * @return PairStats DTO
     */
    public PairStats getPairStats(String pairAddress) {
        Span span = tracer.spanBuilder("AnalyticsService.getPairStats")
                .setAttribute("pair.address", pairAddress)
                .startSpan();
        try {
            logger.debug("Computing statistics for pair: {}", pairAddress);

            PairStats stats = new PairStats();
            stats.setPair(pairAddress);

            // Get pair data from subgraph
            List<PairPayload> pairs = subgraphClient.fetchPairs("sepolia", 100);
            PairPayload pairData = pairs.stream()
                    .filter(p -> p.getId().equalsIgnoreCase(pairAddress))
                    .findFirst()
                    .orElse(null);

            if (pairData == null) {
                logger.warn("Pair not found in subgraph: {}", pairAddress);
                return createEmptyPairStats(pairAddress);
            }

            // Set reserve data
            BigDecimal reserve0 = new BigDecimal(pairData.getReserve0() != null ? pairData.getReserve0() : "0");
            BigDecimal reserve1 = new BigDecimal(pairData.getReserve1() != null ? pairData.getReserve1() : "0");
            stats.setReserve0(reserve0);
            stats.setReserve1(reserve1);

            // Calculate price (reserve1/reserve0)
            BigDecimal price = reserve0.compareTo(BigDecimal.ZERO) > 0
                    ? reserve1.divide(reserve0, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            stats.setPrice(price);

            // Get volumes from tx_records
            BigDecimal volume24h = calculateVolumeFromTxRecords(txRepository.findAll(), 1);
            BigDecimal volume7d = calculateVolumeFromTxRecords(txRepository.findAll(), 7);
            stats.setVolume24h(volume24h);
            stats.setVolume7d(volume7d);

            // Calculate TVL
            BigDecimal tvl = new BigDecimal(pairData.getVolumeUSD() != null ? pairData.getVolumeUSD() : "0");
            stats.setTvl(tvl);

            // Calculate fee APR based on Uniswap v2 model
            // APR = (24h volume * fee rate * 365) / TVL
            BigDecimal feeApr = calculateFeeAPR(volume24h, tvl);
            stats.setFeeApr(feeApr);

            logger.info("Pair stats computed: pair={}, price={}, tvl={}, feeApr={}",
                    pairAddress, price, tvl, feeApr);

            return stats;
        } catch (Exception e) {
            logger.error("Error computing pair stats: {}", e.getMessage(), e);
            span.recordException(e);
            return createEmptyPairStats(pairAddress);
        } finally {
            span.end();
        }
    }

    /**
     * Get user portfolio including recent swaps and net flow.
     *
     * @param userAddress User address
     * @return UserPortfolio DTO
     */
    public UserPortfolio getUserPortfolio(String userAddress) {
        Span span = tracer.spanBuilder("AnalyticsService.getUserPortfolio")
                .setAttribute("user.address", userAddress)
                .startSpan();
        try {
            logger.debug("Computing portfolio for user: {}", userAddress);

            UserPortfolio portfolio = new UserPortfolio();
            portfolio.setAddress(userAddress);

            // Get user's swap transactions from tx_records (decoded_name = "Swap")
            List<TxRecord> userSwaps = txRepository.findAll().stream()
                    .filter(tx -> "Swap".equals(tx.getDecodedName()))
                    .limit(20)
                    .collect(Collectors.toList());

            portfolio.setRecentSwaps(
                    userSwaps.stream()
                            .map(TxRecord::getTxHash)
                            .collect(Collectors.toList())
            );

            // Calculate net flow (rough estimate from swap count)
            // In real system, would parse swap data and calculate actual flows
            BigDecimal netFlowUSD = calculateNetFlow(userSwaps);
            portfolio.setNetFlowUSD(netFlowUSD);

            logger.info("User portfolio computed: user={}, swaps={}, netFlow={}",
                    userAddress, userSwaps.size(), netFlowUSD);

            return portfolio;
        } catch (Exception e) {
            logger.error("Error computing user portfolio: {}", e.getMessage(), e);
            span.recordException(e);
            return createEmptyUserPortfolio(userAddress);
        } finally {
            span.end();
        }
    }

    /**
     * Get overall TVL statistics.
     *
     * @return TvlStats DTO
     */
    public TvlStats getTvlStats() {
        Span span = tracer.spanBuilder("AnalyticsService.getTvlStats").startSpan();
        try {
            logger.debug("Computing TVL statistics");

            TvlStats stats = new TvlStats();

            // Get current TVL from subgraph pairs
            List<PairPayload> pairs = subgraphClient.fetchPairs("sepolia", 100);
            BigDecimal currentTvl = calculateTotalTVL(pairs);
            stats.setTvl(currentTvl);

            // Estimate TVL 24h ago (using older raw events)
            // For demo, use 90% of current TVL
            BigDecimal tvl24hAgo = currentTvl.multiply(new BigDecimal("0.9"));
            stats.setTvl24hAgo(tvl24hAgo);

            // Calculate change rate
            BigDecimal changeRate = currentTvl.compareTo(tvl24hAgo) > 0
                    ? currentTvl.subtract(tvl24hAgo)
                            .divide(tvl24hAgo, 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            stats.setChangeRate(changeRate);

            logger.info("TVL stats computed: tvl={}, change={}%", currentTvl, changeRate);

            return stats;
        } catch (Exception e) {
            logger.error("Error computing TVL stats: {}", e.getMessage(), e);
            span.recordException(e);
            return createEmptyTvlStats();
        } finally {
            span.end();
        }
    }

    // Helper methods

    /**
     * Calculate volume from tx_records within days.
     */
    private BigDecimal calculateVolumeFromTxRecords(List<TxRecord> txRecords, int days) {
        LocalDateTime cutoff = LocalDateTime.now().minus(days, ChronoUnit.DAYS);

        return txRecords.stream()
                .filter(tx -> tx.getCreatedAt() != null && tx.getCreatedAt().isAfter(cutoff))
                .map(tx -> BigDecimal.ONE) // Rough: count swaps
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate token liquidity from subgraph pairs.
     */
    private BigDecimal calculateTokenLiquidity(String tokenAddress) {
        List<PairPayload> pairs = subgraphClient.fetchPairs("sepolia", 100);

        return pairs.stream()
                .filter(p -> p.getToken0() != null &&
                        (p.getToken0().equalsIgnoreCase(tokenAddress) || p.getToken1().equalsIgnoreCase(tokenAddress)))
                .map(p -> new BigDecimal(p.getVolumeUSD() != null ? p.getVolumeUSD() : "0"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Count unique token holders from raw events.
     */
    private Integer countTokenHolders(String tokenAddress) {
        List<RawEvent> events = rawEventRepository.findByChainId("sepolia");

        return (int) events.stream()
                .filter(e -> "Transfer".equals(extractEventName(e.getEventSig())))
                .map(RawEvent::getTxHash)
                .distinct()
                .count();
    }

    /**
     * Calculate fee APR for a pair.
     * APR = (24h volume * fee rate * 365) / TVL
     */
    private BigDecimal calculateFeeAPR(BigDecimal volume24h, BigDecimal tvl) {
        if (tvl.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return volume24h
                .multiply(UNISWAP_V2_FEE_RATE)
                .multiply(DAYS_PER_YEAR)
                .divide(tvl, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * Calculate total TVL from all pairs.
     */
    private BigDecimal calculateTotalTVL(List<PairPayload> pairs) {
        return pairs.stream()
                .map(p -> new BigDecimal(p.getVolumeUSD() != null ? p.getVolumeUSD() : "0"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate net flow for user (rough estimate).
     */
    private BigDecimal calculateNetFlow(List<TxRecord> userSwaps) {
        // Rough calculation: number of swaps * estimated average flow
        // In real system, would parse swap data
        return BigDecimal.valueOf(userSwaps.size()).multiply(new BigDecimal("100"));
    }

    /**
     * Extract event name from event signature.
     */
    private String extractEventName(String eventSig) {
        if (eventSig == null) return "unknown";

        if (eventSig.equalsIgnoreCase("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")) {
            return "Transfer";
        }
        if (eventSig.equalsIgnoreCase("0xd78ad95fa46ab8d7b872519e710f362c7dea70131084f770ccee07fc7a1d580f")) {
            return "Swap";
        }
        return "unknown";
    }

    // Empty DTO factories

    private TokenStats createEmptyTokenStats(String tokenAddress) {
        return new TokenStats(tokenAddress, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private PairStats createEmptyPairStats(String pairAddress) {
        return new PairStats(pairAddress, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private UserPortfolio createEmptyUserPortfolio(String userAddress) {
        return new UserPortfolio(userAddress, BigDecimal.ZERO, Collections.emptyList());
    }

    private TvlStats createEmptyTvlStats() {
        return new TvlStats(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
