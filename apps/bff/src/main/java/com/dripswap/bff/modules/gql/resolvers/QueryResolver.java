package com.dripswap.bff.modules.gql.resolvers;

import com.dripswap.bff.modules.chains.model.RawEvent;
import com.dripswap.bff.modules.gql.model.*;
import com.dripswap.bff.modules.subgraph.SubgraphClient;
import com.dripswap.bff.modules.rest.model.DemoTx;
import com.dripswap.bff.modules.analytics.service.AnalyticsService;
import com.dripswap.bff.modules.analytics.dto.*;
import com.dripswap.bff.modules.tx.model.TxRecord;
import com.dripswap.bff.modules.tx.repository.TxRepository;
import com.dripswap.bff.repository.DemoTxRepository;
import com.dripswap.bff.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GraphQL Query Resolver.
 * Aggregates RawEventRepository + TxRepository + SubgraphClient.
 */
@Controller
public class QueryResolver {

    private static final Logger logger = LoggerFactory.getLogger(QueryResolver.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RawEventRepository rawEventRepository;
    private final TxRepository txRepository;
    private final DemoTxRepository demoTxRepository;
    private final SubgraphClient subgraphClient;
    private final AnalyticsService analyticsService;

    public QueryResolver(RawEventRepository rawEventRepository,
                        TxRepository txRepository,
                        DemoTxRepository demoTxRepository,
                        SubgraphClient subgraphClient,
                        AnalyticsService analyticsService) {
        this.rawEventRepository = rawEventRepository;
        this.txRepository = txRepository;
        this.demoTxRepository = demoTxRepository;
        this.subgraphClient = subgraphClient;
        this.analyticsService = analyticsService;
    }

    /**
     * Health check endpoint.
     */
    @QueryMapping
    public String ping() {
        logger.debug("Ping query received");
        return "pong";
    }

    /**
     * Get latest raw events from blockchain.
     */
    @QueryMapping
    public List<RawEventPayload> latestRawEvents(
            @Argument String chainId,
            @Argument(name = "limit") Integer limitArg) {
        try {
            int limit = (limitArg != null && limitArg > 0) ? limitArg : 10;
            logger.debug("Fetching latest {} raw events for chain: {}", limit, chainId);

            Pageable pageable = PageRequest.of(0, limit, Sort.by("id").descending());
            List<RawEvent> events = rawEventRepository.findByChainId(chainId);

            return events.stream()
                    .sorted((a, b) -> b.getId().compareTo(a.getId()))
                    .limit(limit)
                    .map(this::convertToRawEventPayload)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching raw events: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get recent parsed transactions.
     */
    @QueryMapping
    public List<TransactionPayload> recentTransactions(
            @Argument String chainId,
            @Argument(name = "limit") Integer limitArg) {
        try {
            int limit = (limitArg != null && limitArg > 0) ? limitArg : 10;
            logger.debug("Fetching {} recent transactions for chain: {}", limit, chainId);

            List<TxRecord> txRecords = txRepository.findByChainId(chainId);

            return txRecords.stream()
                    .sorted((a, b) -> b.getId().compareTo(a.getId()))
                    .limit(limit)
                    .map(this::convertToTransactionPayload)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching transactions: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get subgraph synchronization status.
     */
    @QueryMapping
    public SubgraphStatusPayload subgraphStatus(@Argument String chainId) {
        try {
            logger.debug("Fetching subgraph status for chain: {}", chainId);

            SubgraphStatusPayload status = new SubgraphStatusPayload();
            status.setChainId(chainId);

            boolean isHealthy = subgraphClient.isHealthy(chainId);
            status.setIsHealthy(isHealthy);

            Long syncedBlock = subgraphClient.getSyncedBlock(chainId);
            status.setSyncedBlock(syncedBlock);

            if (isHealthy) {
                status.setMessage("Subgraph is synced and healthy");
                status.setIndexingErrorCount(0);
            } else {
                status.setMessage("Subgraph has indexing errors");
                status.setIndexingErrorCount(1);
            }

            return status;
        } catch (Exception e) {
            logger.error("Error fetching subgraph status: {}", e.getMessage(), e);
            SubgraphStatusPayload status = new SubgraphStatusPayload();
            status.setChainId(chainId);
            status.setIsHealthy(false);
            status.setMessage("Error fetching subgraph status: " + e.getMessage());
            return status;
        }
    }

    /**
     * Get trading pairs from subgraph.
     */
    @QueryMapping
    public List<PairPayload> pairs(
            @Argument String chainId,
            @Argument(name = "filter") Map<String, Object> filterArg) {
        try {
            logger.debug("Fetching pairs for chain: {}", chainId);

            List<PairPayload> pairs = subgraphClient.fetchPairs(chainId, 50);

            // Apply simple filtering if provided
            if (filterArg != null && !filterArg.isEmpty()) {
                pairs = applyPairFilters(pairs, filterArg);
            }

            return pairs;
        } catch (Exception e) {
            logger.error("Error fetching pairs: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get tokens from subgraph.
     */
    @QueryMapping
    public List<TokenPayload> tokens(
            @Argument String chainId,
            @Argument(name = "filter") Map<String, Object> filterArg) {
        try {
            logger.debug("Fetching tokens for chain: {}", chainId);

            List<TokenPayload> tokens = subgraphClient.fetchTokens(chainId, 50);

            // Apply simple filtering if provided
            if (filterArg != null && !filterArg.isEmpty()) {
                tokens = applyTokenFilters(tokens, filterArg);
            }

            return tokens;
        } catch (Exception e) {
            logger.error("Error fetching tokens: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get user portfolio summary.
     */
    @QueryMapping
    public UserPortfolioPayload userPortfolio(@Argument String address) {
        try {
            logger.debug("Fetching portfolio for address: {}", address);

            UserPortfolioPayload portfolio = new UserPortfolioPayload();
            portfolio.setAddress(address);
            portfolio.setTotalValueUSD("0");
            portfolio.setHoldings(new ArrayList<>());

            // Placeholder: In real implementation, query subgraph for user holdings
            // and transaction history for this address

            // Get transaction count for this address from tx_records
            // This is a placeholder - actual implementation would need user transaction history
            portfolio.setTransactionCount(0);

            logger.info("Portfolio fetched for address: {}", address);
            return portfolio;
        } catch (Exception e) {
            logger.error("Error fetching portfolio: {}", e.getMessage(), e);
            UserPortfolioPayload portfolio = new UserPortfolioPayload();
            portfolio.setAddress(address);
            portfolio.setTotalValueUSD("0");
            portfolio.setHoldings(Collections.emptyList());
            portfolio.setTransactionCount(0);
            return portfolio;
        }
    }

    /**
     * Get recent demo transactions.
     */
    @QueryMapping
    public List<DemoTxPayload> recentDemoTx(@Argument(name = "limit") Integer limitArg) {
        try {
            int limit = (limitArg != null && limitArg > 0) ? limitArg : 10;
            logger.debug("Fetching {} recent demo transactions", limit);

            List<DemoTx> demoTxs = demoTxRepository.findAll();

            return demoTxs.stream()
                    .sorted((a, b) -> b.getId().compareTo(a.getId()))
                    .limit(limit)
                    .map(this::convertToDemoTxPayload)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching demo transactions: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get token analytics.
     */
    @QueryMapping
    public TokenStats analyticsToken(@Argument String token) {
        try {
            logger.debug("Fetching token analytics: {}", token);
            TokenStats result = analyticsService.getTokenStats(token);
            logger.info("Token analytics retrieved: {}", token);
            return result;
        } catch (Exception e) {
            logger.error("Error fetching token analytics: {}", e.getMessage(), e);
            return new TokenStats(token, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 
                    java.math.BigDecimal.ZERO, 0);
        }
    }

    /**
     * Get pair analytics.
     */
    @QueryMapping
    public PairStats analyticsPair(@Argument String pair) {
        try {
            logger.debug("Fetching pair analytics: {}", pair);
            return analyticsService.getPairStats(pair);
        } catch (Exception e) {
            logger.error("Error fetching pair analytics: {}", e.getMessage(), e);
            return new PairStats(pair, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        }
    }

    /**
     * Get user portfolio analytics.
     */
    @QueryMapping
    public UserPortfolio analyticsUser(@Argument String user) {
        try {
            logger.debug("Fetching user analytics: {}", user);
            return analyticsService.getUserPortfolio(user);
        } catch (Exception e) {
            logger.error("Error fetching user analytics: {}", e.getMessage(), e);
            return new UserPortfolio(user, java.math.BigDecimal.ZERO, Collections.emptyList());
        }
    }

    /**
     * Get TVL analytics.
     */
    @QueryMapping
    public TvlStats analyticsTvl() {
        try {
            logger.debug("Fetching TVL analytics");
            return analyticsService.getTvlStats();
        } catch (Exception e) {
            logger.error("Error fetching TVL analytics: {}", e.getMessage(), e);
            return new TvlStats(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 
                    java.math.BigDecimal.ZERO);
        }
    }

    // Helper methods

    /**
     * Convert RawEvent entity to GraphQL payload.
     */
    private RawEventPayload convertToRawEventPayload(RawEvent event) {
        RawEventPayload payload = new RawEventPayload();
        payload.setId(event.getId());
        payload.setChainId(event.getChainId());
        payload.setBlockNumber(event.getBlockNumber());
        payload.setTxHash(event.getTxHash());
        payload.setLogIndex(event.getLogIndex());
        payload.setEventSig(event.getEventSig());
        payload.setRawData(event.getRawData());

        if (event.getCreatedAt() != null) {
            payload.setCreatedAt(event.getCreatedAt().format(DATE_FORMATTER));
        }

        return payload;
    }

    /**
     * Convert TxRecord entity to GraphQL payload.
     */
    private TransactionPayload convertToTransactionPayload(TxRecord txRecord) {
        TransactionPayload payload = new TransactionPayload();
        payload.setId(txRecord.getId());
        payload.setChainId(txRecord.getChainId());
        payload.setBlockNumber(txRecord.getBlockNumber());
        payload.setTxHash(txRecord.getTxHash());
        payload.setEventSig(txRecord.getEventSig());
        payload.setDecodedName(txRecord.getDecodedName());
        payload.setDecodedData(txRecord.getDecodedData());
        payload.setStatus(txRecord.getStatus());

        if (txRecord.getCreatedAt() != null) {
            payload.setCreatedAt(txRecord.getCreatedAt().format(DATE_FORMATTER));
        }

        return payload;
    }

    /**
     * Apply filters to pairs list.
     */
    private List<PairPayload> applyPairFilters(List<PairPayload> pairs, Map<String, Object> filters) {
        return pairs.stream()
                .filter(pair -> {
                    // volumeUSDMin filter
                    if (filters.containsKey("volumeUSDMin")) {
                        try {
                            String minStr = filters.get("volumeUSDMin").toString();
                            double minValue = Double.parseDouble(minStr);
                            double volumeValue = Double.parseDouble(pair.getVolumeUSD());
                            if (volumeValue < minValue) {
                                return false;
                            }
                        } catch (Exception e) {
                            logger.debug("Error parsing volumeUSDMin filter", e);
                        }
                    }

                    // volumeUSDMax filter
                    if (filters.containsKey("volumeUSDMax")) {
                        try {
                            String maxStr = filters.get("volumeUSDMax").toString();
                            double maxValue = Double.parseDouble(maxStr);
                            double volumeValue = Double.parseDouble(pair.getVolumeUSD());
                            if (volumeValue > maxValue) {
                                return false;
                            }
                        } catch (Exception e) {
                            logger.debug("Error parsing volumeUSDMax filter", e);
                        }
                    }

                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Apply filters to tokens list.
     */
    private List<TokenPayload> applyTokenFilters(List<TokenPayload> tokens, Map<String, Object> filters) {
        return tokens.stream()
                .filter(token -> {
                    // volumeUSDMin filter
                    if (filters.containsKey("volumeUSDMin")) {
                        try {
                            String minStr = filters.get("volumeUSDMin").toString();
                            double minValue = Double.parseDouble(minStr);
                            double volumeValue = Double.parseDouble(token.getVolumeUSD());
                            if (volumeValue < minValue) {
                                return false;
                            }
                        } catch (Exception e) {
                            logger.debug("Error parsing volumeUSDMin filter", e);
                        }
                    }

                    // decimalsMin filter
                    if (filters.containsKey("decimalsMin") && token.getDecimals() != null) {
                        try {
                            Integer minDecimals = Integer.parseInt(filters.get("decimalsMin").toString());
                            if (token.getDecimals() < minDecimals) {
                                return false;
                            }
                        } catch (Exception e) {
                            logger.debug("Error parsing decimalsMin filter", e);
                        }
                    }

                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert DemoTx entity to GraphQL payload.
     */
    private DemoTxPayload convertToDemoTxPayload(DemoTx demoTx) {
        DemoTxPayload payload = new DemoTxPayload();
        payload.setId(demoTx.getId());
        payload.setTxHash(demoTx.getTxHash());
        payload.setChainId(demoTx.getChainId());
        payload.setStatus(demoTx.getStatus());

        if (demoTx.getCreatedAt() != null) {
            payload.setCreatedAt(demoTx.getCreatedAt().format(DATE_FORMATTER));
        }

        return payload;
    }
}
