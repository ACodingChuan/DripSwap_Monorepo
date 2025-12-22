package com.dripswap.bff.gql;

import com.dripswap.bff.entity.BridgeTransfer;
import com.dripswap.bff.entity.Burn;
import com.dripswap.bff.entity.Bundle;
import com.dripswap.bff.entity.Mint;
import com.dripswap.bff.entity.Pair;
import com.dripswap.bff.entity.PairTokenLookup;
import com.dripswap.bff.entity.Swap;
import com.dripswap.bff.entity.Transaction;
import com.dripswap.bff.entity.Token;
import com.dripswap.bff.entity.TokenDayData;
import com.dripswap.bff.entity.TokenHourData;
import com.dripswap.bff.entity.TokenMinuteData;
import com.dripswap.bff.entity.UniswapDayData;
import com.dripswap.bff.entity.UniswapFactory;
import com.dripswap.bff.gql.payload.ExploreSeriesPointPayload;
import com.dripswap.bff.gql.payload.ExploreStatsPayload;
import com.dripswap.bff.gql.payload.ExploreTokenRowPayload;
import com.dripswap.bff.gql.payload.RawEventPayload;
import com.dripswap.bff.gql.payload.TokenDetailsPayload;
import com.dripswap.bff.gql.payload.TokenLitePayload;
import com.dripswap.bff.gql.payload.TokenOhlcPayload;
import com.dripswap.bff.gql.payload.TokenPoolRowPayload;
import com.dripswap.bff.gql.payload.TokenTransactionRowPayload;
import com.dripswap.bff.gql.payload.TransactionPayload;
import com.dripswap.bff.repository.BridgeTransferRepository;
import com.dripswap.bff.repository.BurnRepository;
import com.dripswap.bff.repository.BundleRepository;
import com.dripswap.bff.repository.MintRepository;
import com.dripswap.bff.repository.PairRepository;
import com.dripswap.bff.repository.PairTokenLookupRepository;
import com.dripswap.bff.repository.SwapRepository;
import com.dripswap.bff.repository.TokenDayDataRepository;
import com.dripswap.bff.repository.TokenHourDataRepository;
import com.dripswap.bff.repository.TokenMinuteDataRepository;
import com.dripswap.bff.repository.TokenRepository;
import com.dripswap.bff.repository.TransactionRepository;
import com.dripswap.bff.repository.UniswapDayDataRepository;
import com.dripswap.bff.repository.UniswapFactoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Controller
@RequiredArgsConstructor
public class QueryResolver {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final SwapRepository swapRepository;
    private final MintRepository mintRepository;
    private final BurnRepository burnRepository;
    private final BridgeTransferRepository bridgeTransferRepository;
    private final TransactionRepository transactionRepository;
    private final PairRepository pairRepository;
    private final TokenRepository tokenRepository;
    private final BundleRepository bundleRepository;
    private final TokenHourDataRepository tokenHourDataRepository;
    private final TokenDayDataRepository tokenDayDataRepository;
    private final TokenMinuteDataRepository tokenMinuteDataRepository;
    private final PairTokenLookupRepository pairTokenLookupRepository;
    private final UniswapFactoryRepository uniswapFactoryRepository;
    private final UniswapDayDataRepository uniswapDayDataRepository;
    
    // Redis TTL constants (按QUERY-AND-SYNC-STRATEGY.md规范)
    private static final long TTL_RECENT_TX_SECONDS = 30 * 60;      // 30分钟
    private static final long TTL_STATS_SECONDS = 60;               // 1分钟
    private static final long TTL_TOKEN_LIST_SECONDS = 60;          // 1分钟
    private static final int MAX_CANDLE_POINTS = 5000;

    @QueryMapping
    public String ping() {
        return "pong";
    }

    /**
     * NOTE: raw_events table/entity is not implemented yet in this repo snapshot.
     * Return empty list to satisfy non-null GraphQL contract and avoid frontend failures.
     */
    @QueryMapping
    public List<RawEventPayload> latestRawEvents(@Argument String chainId, @Argument Integer limit) {
        return List.of();
    }

    /**
     * Recent transactions for Explore page.
     * Redis-first: ds:v2:{chain}:tx:global:recent
     *
     * <p>Current schema defines TransactionPayload as a "parsed tx record". We don't have a
     * dedicated tx_records table/entity yet, so we synthesize a recent stream from the existing
     * fact tables: swaps/mints/burns/bridge_transfers, and enrich blockNumber via transactions.</p>
     */
    @QueryMapping
    public List<TransactionPayload> recentTransactions(@Argument String chainId, @Argument Integer limit) {
        try {
            String normalizedChainId = normalizeChainId(chainId);
            int size = limit == null ? 25 : Math.max(1, Math.min(limit, 200));
            
            // Redis key: ds:v2:{chain}:tx:global:recent:{limit}
            String redisKey = String.format("ds:v2:%s:tx:global:recent:%d", normalizedChainId, size);
            
            // 1. Try Redis first
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null && !cached.isEmpty()) {
                try {
                    List<TransactionPayload> result = objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TransactionPayload.class)
                    );
                    log.debug("Redis HIT: {} - returned {} transactions", redisKey, result.size());
                    return result;
                } catch (Exception e) {
                    log.warn("Redis deserialization failed for key: {}, fallback to DB", redisKey, e);
                }
            }
            
            // 2. Redis miss - query DB
            log.debug("Redis MISS: {} - querying DB", redisKey);

            // Uniswap "Transactions" tab is swaps-only for the V2 analytics context.
            // We keep the existing schema name `recentTransactions`, but the returned stream is swaps-only.
            List<Swap> swaps = swapRepository.findByChainId(
                    normalizedChainId,
                    PageRequest.of(
                            0,
                            size,
                            Sort.by(Sort.Direction.DESC, "timestamp")
                                    .and(Sort.by(Sort.Direction.DESC, "logIndex"))
                    )
            );

            Set<String> txIds = swaps.stream().map(Swap::getTransactionId).collect(Collectors.toSet());
            Map<String, Transaction> txMap = txIds.isEmpty()
                    ? Map.of()
                    : transactionRepository.findByChainIdAndIdIn(normalizedChainId, txIds)
                    .stream()
                    .collect(Collectors.toMap(Transaction::getId, t -> t, (a, b) -> a));

            List<TransactionPayload> result = new ArrayList<>(swaps.size());
            for (Swap swap : swaps) {
                Transaction tx = txMap.get(swap.getTransactionId());
                long blockNumber = tx == null ? 0L : tx.getBlockNumber();

                String decodedData = buildJson(buildSwapDecodedData(normalizedChainId, swap));
                result.add(TransactionPayload.builder()
                        .id("swap:" + swap.getId())
                        .chainId(normalizedChainId)
                        .blockNumber(blockNumber)
                        .txHash(swap.getTransactionId())
                        .eventSig(null)
                        .decodedName("Swap")
                        .decodedData(decodedData)
                        .status("indexed")
                        .createdAt(String.valueOf(swap.getTimestamp()))
                        .build());
            }
            
            // 3. Write back to Redis with TTL
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(redisKey, json, 
                    java.time.Duration.ofSeconds(TTL_RECENT_TX_SECONDS));
                log.debug("Redis SET: {} - cached {} transactions for {}s", 
                    redisKey, result.size(), TTL_RECENT_TX_SECONDS);
            } catch (Exception e) {
                log.warn("Failed to cache recentTransactions to Redis: {}", redisKey, e);
            }

            return result;
        } catch (Exception e) {
            log.error("recentTransactions failed: chainId={}, limit={}", chainId, limit, e);
            return List.of();
        }
    }

    @QueryMapping
    public ExploreStatsPayload exploreStats(@Argument String chainId, @Argument Integer days) {
        String normalizedChainId = normalizeChainId(chainId);
        int windowDays = days == null ? 30 : Math.max(1, Math.min(days, 90));
        
        // Redis key: ds:v2:{chain}:explore:stats:{days}
        String redisKey = String.format("ds:v2:%s:explore:stats:%d", normalizedChainId, windowDays);
        
        // 1. Try Redis first
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                ExploreStatsPayload result = objectMapper.readValue(cached, ExploreStatsPayload.class);
                log.debug("Redis HIT: {} - returned stats", redisKey);
                return result;
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to DB", redisKey, e);
            }
        }
        
        // 2. Redis miss - query DB
        log.debug("Redis MISS: {} - querying DB", redisKey);

        UniswapFactory factory = uniswapFactoryRepository
                .findFirstByChainIdOrderByUpdatedAtDesc(normalizedChainId)
                .orElse(null);

        BigDecimal tvlUsd = factory == null ? BigDecimal.ZERO : safeBigDecimal(factory.getTotalLiquidityUsd());

        UniswapDayData latestDay = uniswapDayDataRepository
                .findFirstByChainIdOrderByDateDesc(normalizedChainId)
                .orElse(null);

        BigDecimal volume24hUsd = latestDay == null ? BigDecimal.ZERO : safeBigDecimal(latestDay.getDailyVolumeUsd());
        BigDecimal fees24hUsd = volume24hUsd.multiply(new BigDecimal("0.003"));

        List<UniswapDayData> seriesRows = uniswapDayDataRepository.findByChainIdOrderByDateDesc(
                normalizedChainId,
                PageRequest.of(0, windowDays)
        );

        List<ExploreSeriesPointPayload> tvlSeries = seriesRows.stream()
                .sorted(Comparator.comparingInt(UniswapDayData::getDate))
                .map(row -> ExploreSeriesPointPayload.builder()
                        .date(row.getDate())
                        .valueUsd(safeBigDecimal(row.getTotalLiquidityUsd()))
                        .build())
                .toList();

        List<ExploreSeriesPointPayload> volumeSeries = seriesRows.stream()
                .sorted(Comparator.comparingInt(UniswapDayData::getDate))
                .map(row -> ExploreSeriesPointPayload.builder()
                        .date(row.getDate())
                        .valueUsd(safeBigDecimal(row.getDailyVolumeUsd()))
                        .build())
                .toList();

        ExploreStatsPayload result = ExploreStatsPayload.builder()
                .chainId(normalizedChainId)
                .tvlUsd(tvlUsd)
                .volume24hUsd(volume24hUsd)
                .fees24hUsd(fees24hUsd)
                .tvlSeries(tvlSeries)
                .volumeSeries(volumeSeries)
                .build();
        
        // 3. Write back to Redis with TTL
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json,
                java.time.Duration.ofSeconds(TTL_STATS_SECONDS));
            log.debug("Redis SET: {} - cached stats for {}s", redisKey, TTL_STATS_SECONDS);
        } catch (Exception e) {
            log.warn("Failed to cache exploreStats to Redis: {}", redisKey, e);
        }
        
        return result;
    }

    @QueryMapping
    public List<ExploreTokenRowPayload> exploreTokens(
            @Argument String chainId,
            @Argument Integer limit,
            @Argument String search
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        
        // Redis key: ds:v2:{chain}:tokens:list:{limit}:{searchHash}
        String searchKey = (search == null || search.trim().isEmpty()) 
            ? "all" 
            : Integer.toHexString(search.trim().toLowerCase().hashCode());
        String redisKey = String.format("ds:v2:%s:tokens:list:%d:%s", 
            normalizedChainId, size, searchKey);
        
        // 1. Try Redis first
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                List<ExploreTokenRowPayload> result = objectMapper.readValue(
                    cached,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ExploreTokenRowPayload.class)
                );
                log.debug("Redis HIT: {} - returned {} tokens", redisKey, result.size());
                return result;
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to DB", redisKey, e);
            }
        }
        
        // 2. Redis miss - query DB
        log.debug("Redis MISS: {} - querying DB", redisKey);

        List<Token> base = tokenRepository.findByChainIdOrderByTradeVolumeUsdDesc(normalizedChainId);
        Stream<Token> stream = base.stream();

        if (search != null && !search.trim().isEmpty()) {
            String q = search.trim().toLowerCase(Locale.ROOT);
            stream = stream.filter(token ->
                    token.getId().toLowerCase(Locale.ROOT).contains(q)
                            || token.getSymbol().toLowerCase(Locale.ROOT).contains(q)
                            || token.getName().toLowerCase(Locale.ROOT).contains(q)
            );
        }

        List<ExploreTokenRowPayload> result = stream
                .limit(size)
                .map(token -> toExploreTokenRow(normalizedChainId, token))
                .toList();
        
        // 3. Write back to Redis with TTL
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json,
                java.time.Duration.ofSeconds(TTL_TOKEN_LIST_SECONDS));
            log.debug("Redis SET: {} - cached {} tokens for {}s", 
                redisKey, result.size(), TTL_TOKEN_LIST_SECONDS);
        } catch (Exception e) {
            log.warn("Failed to cache exploreTokens to Redis: {}", redisKey, e);
        }
        
        return result;
    }

    @QueryMapping
    public TokenDetailsPayload tokenDetails(@Argument String chainId, @Argument String tokenAddress) {
        String normalizedChainId = normalizeChainId(chainId);
        String tokenId = tokenAddress == null ? "" : tokenAddress.trim().toLowerCase(Locale.ROOT);
        if (tokenId.isEmpty()) {
            return null;
        }

        Token token = tokenRepository.findByIdAndChainId(tokenId, normalizedChainId).orElse(null);
        if (token == null) {
            return null;
        }

        Bundle bundle = bundleRepository.findById(bundleId(normalizedChainId)).orElse(null);
        BigDecimal ethPrice = bundle == null ? BigDecimal.ZERO : safeBigDecimal(bundle.getEthPrice());
        BigDecimal priceUsd = safeBigDecimal(token.getDerivedEth()).multiply(ethPrice);

        TokenHourData latestHour = tokenHourDataRepository
                .findFirstByChainIdAndTokenIdOrderByPeriodStartUnixDesc(normalizedChainId, tokenId)
                .orElse(null);

        long nowSec = System.currentTimeMillis() / 1000;
        boolean isHourDataFresh = latestHour != null && latestHour.getPeriodStartUnix() != null
                && latestHour.getPeriodStartUnix() >= (int) (nowSec - 2 * 86400L);

        BigDecimal tvlUsd = latestHour == null ? BigDecimal.ZERO : safeBigDecimal(latestHour.getTotalValueLockedUsd());

        BigDecimal change24hPct = null;
        BigDecimal volume24hUsd = BigDecimal.ZERO;

        if (isHourDataFresh) {
            int latestHourStart = latestHour.getPeriodStartUnix();
            int target = latestHourStart - 86400;

            TokenHourData prevHour = tokenHourDataRepository
                    .findFirstByChainIdAndTokenIdAndPeriodStartUnixLessThanEqualOrderByPeriodStartUnixDesc(
                            normalizedChainId,
                            tokenId,
                            target
                    )
                    .orElse(null);

            change24hPct = percentChange(
                    prevHour == null ? null : prevHour.getClose(),
                    latestHour.getClose()
            );

            List<TokenHourData> last24h = tokenHourDataRepository
                    .findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
                            normalizedChainId,
                            tokenId,
                            target,
                            latestHourStart
                    );

            BigDecimal sum = BigDecimal.ZERO;
            for (TokenHourData row : last24h) {
                sum = sum.add(row.getVolumeUsd() == null ? BigDecimal.ZERO : row.getVolumeUsd());
            }
            volume24hUsd = sum;
        }

        BigDecimal fdvUsd = null;
        if (token.getDecimals() != null && token.getTotalSupply() != null && priceUsd.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal divisor = BigDecimal.TEN.pow(Math.max(0, token.getDecimals()));
            BigDecimal scaledSupply = token.getTotalSupply().divide(divisor, 8, RoundingMode.HALF_UP);
            fdvUsd = scaledSupply.multiply(priceUsd);
        }

        return TokenDetailsPayload.builder()
                .chainId(normalizedChainId)
                .address(token.getId())
                .symbol(token.getSymbol())
                .name(token.getName())
                .decimals(token.getDecimals())
                .priceUsd(priceUsd)
                .change24hPct(change24hPct)
                .tvlUsd(tvlUsd)
                .volume24hUsd(volume24hUsd)
                .fdvUsd(fdvUsd)
                .build();
    }

    @QueryMapping
    public List<TokenOhlcPayload> tokenPriceCandles(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument TokenChartInterval interval,
            @Argument Integer from,
            @Argument Integer to
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        String tokenId = tokenAddress == null ? "" : tokenAddress.trim().toLowerCase(Locale.ROOT);
        if (tokenId.isEmpty() || interval == null || from == null || to == null) {
            return List.of();
        }

        int start = Math.min(from, to);
        int end = Math.max(from, to);

        try {
            return switch (interval) {
                case MINUTE -> toOhlcFromMinuteData(
                        tokenMinuteDataRepository.findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
                                normalizedChainId,
                                tokenId,
                                start,
                                end
                        )
                );
                case HOUR -> toOhlcFromHourData(
                        tokenHourDataRepository.findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
                                normalizedChainId,
                                tokenId,
                                start,
                                end
                        )
                );
                case DAY -> aggregateDailyFromHourData(
                        tokenHourDataRepository.findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
                                normalizedChainId,
                                tokenId,
                                start,
                                end
                        )
                );
            };
        } catch (Exception e) {
            log.error(
                    "tokenPriceCandles failed: chainId={}, tokenAddress={}, interval={}, from={}, to={}",
                    chainId,
                    tokenAddress,
                    interval,
                    from,
                    to,
                    e
            );
            return List.of();
        }
    }

    @QueryMapping
    public List<TokenPoolRowPayload> tokenPools(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument Integer limit
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        String tokenId = tokenAddress == null ? "" : tokenAddress.trim().toLowerCase(Locale.ROOT);
        if (tokenId.isEmpty()) {
            return List.of();
        }

        int size = limit == null ? 10 : Math.max(1, Math.min(limit, 50));

        List<Pair> pairs = findPairsForToken(normalizedChainId, tokenId, 500);
        if (pairs.isEmpty()) {
            return List.of();
        }

        List<Pair> topPairs = pairs.stream()
                .sorted(Comparator.comparing((Pair p) -> safeBigDecimal(p.getReserveUsd())).reversed())
                .limit(size)
                .toList();

        Map<String, Token> tokenById = findTokenMetaMap(
                normalizedChainId,
                topPairs.stream()
                        .flatMap(p -> Stream.of(p.getToken0Id(), p.getToken1Id()))
                        .filter(Objects::nonNull)
                        .map(id -> id.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList()
        );

        return topPairs.stream()
                .map(pair -> TokenPoolRowPayload.builder()
                        .pairAddress(pair.getId())
                        .token0(tokenLite(pair.getToken0Id(), tokenById.get(pair.getToken0Id())))
                        .token1(tokenLite(pair.getToken1Id(), tokenById.get(pair.getToken1Id())))
                        .tvlUsd(safeBigDecimal(pair.getReserveUsd()))
                        .volumeUsd(safeBigDecimal(pair.getVolumeUsd()))
                        .build())
                .toList();
    }

    @QueryMapping
    public List<TokenTransactionRowPayload> tokenTransactions(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument Integer limit
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        String tokenId = tokenAddress == null ? "" : tokenAddress.trim().toLowerCase(Locale.ROOT);
        if (tokenId.isEmpty()) {
            return List.of();
        }

        int size = limit == null ? 25 : Math.max(1, Math.min(limit, 100));

        List<Pair> pairs = findPairsForToken(normalizedChainId, tokenId, 500);
        if (pairs.isEmpty()) {
            return List.of();
        }

        List<String> topPairIds = pairs.stream()
                .sorted(Comparator.comparing((Pair p) -> safeBigDecimal(p.getReserveUsd())).reversed())
                .limit(50)
                .map(Pair::getId)
                .toList();

        List<Swap> swaps = swapRepository.findByChainIdAndPairIdInOrderByTimestampDesc(
                normalizedChainId,
                topPairIds,
                PageRequest.of(0, size)
        );

        if (swaps.isEmpty()) {
            return List.of();
        }

        Map<String, Pair> pairById = pairRepository.findByChainIdAndIdIn(normalizedChainId, swaps.stream()
                        .map(Swap::getPairId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(Pair::getId, p -> p));

        Map<String, Token> tokenById = findTokenMetaMap(
                normalizedChainId,
                pairById.values().stream()
                        .flatMap(p -> Stream.of(p.getToken0Id(), p.getToken1Id()))
                        .filter(Objects::nonNull)
                        .map(id -> id.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList()
        );

        return swaps.stream()
                .map(swap -> {
                    Pair pair = pairById.get(swap.getPairId());
                    if (pair == null) {
                        return null;
                    }

                    return TokenTransactionRowPayload.builder()
                            .id(swap.getId())
                            .timestamp(swap.getTimestamp())
                            .txHash(swap.getTransactionId())
                            .pairAddress(swap.getPairId())
                            .amountUsd(safeBigDecimal(swap.getAmountUsd()))
                            .token0(tokenLite(pair.getToken0Id(), tokenById.get(pair.getToken0Id())))
                            .token1(tokenLite(pair.getToken1Id(), tokenById.get(pair.getToken1Id())))
                            .amount0In(safeBigDecimal(swap.getAmount0In()))
                            .amount1In(safeBigDecimal(swap.getAmount1In()))
                            .amount0Out(safeBigDecimal(swap.getAmount0Out()))
                            .amount1Out(safeBigDecimal(swap.getAmount1Out()))
                            .build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private ExploreTokenRowPayload toExploreTokenRow(String chainId, Token token) {
        return ExploreTokenRowPayload.builder()
                .id(token.getId())
                .chainId(chainId)
                .symbol(token.getSymbol())
                .name(token.getName())
                .decimals(token.getDecimals())
                .totalSupply(token.getTotalSupply())
                .derivedETH(token.getDerivedEth())
                .build();
    }

    private BigDecimal percentChange(BigDecimal base, BigDecimal current) {
        if (base == null || current == null) return null;
        if (base.compareTo(BigDecimal.ZERO) == 0) return null;
        // (current - base) / base * 100
        return current.subtract(base)
                .divide(base, 8, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private com.dripswap.bff.entity.ChainEntityId bundleId(String chainId) {
        com.dripswap.bff.entity.ChainEntityId id = new com.dripswap.bff.entity.ChainEntityId();
        id.setChainId(chainId);
        id.setId("1");
        return id;
    }

    private List<TokenOhlcPayload> toOhlcFromMinuteData(List<TokenMinuteData> rows) {
        return downsample(rows, row -> TokenOhlcPayload.builder()
                .timestamp(row.getPeriodStartUnix())
                .open(safeBigDecimal(row.getOpen()))
                .high(safeBigDecimal(row.getHigh()))
                .low(safeBigDecimal(row.getLow()))
                .close(safeBigDecimal(row.getClose()))
                .volumeUsd(safeBigDecimal(row.getVolumeUsd()))
                .tvlUsd(safeBigDecimal(row.getTotalValueLockedUsd()))
                .build());
    }

    private List<TokenOhlcPayload> toOhlcFromHourData(List<TokenHourData> rows) {
        return downsample(rows, row -> TokenOhlcPayload.builder()
                .timestamp(row.getPeriodStartUnix())
                .open(safeBigDecimal(row.getOpen()))
                .high(safeBigDecimal(row.getHigh()))
                .low(safeBigDecimal(row.getLow()))
                .close(safeBigDecimal(row.getClose()))
                .volumeUsd(safeBigDecimal(row.getVolumeUsd()))
                .tvlUsd(safeBigDecimal(row.getTotalValueLockedUsd()))
                .build());
    }

    private List<TokenOhlcPayload> aggregateDailyFromHourData(List<TokenHourData> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Integer, AggregatedOhlc> byDay = new LinkedHashMap<>();
        for (TokenHourData row : rows) {
            int dayStart = (row.getPeriodStartUnix() / 86400) * 86400;
            AggregatedOhlc bucket = byDay.computeIfAbsent(dayStart, k -> new AggregatedOhlc(dayStart));
            bucket.add(row);
        }

        List<TokenOhlcPayload> result = byDay.values().stream()
                .map(AggregatedOhlc::toPayload)
                .toList();

        return result.size() > MAX_CANDLE_POINTS ? result.subList(result.size() - MAX_CANDLE_POINTS, result.size()) : result;
    }

    private <T> List<TokenOhlcPayload> downsample(List<T> rows, java.util.function.Function<T, TokenOhlcPayload> mapper) {
        if (rows.isEmpty()) {
            return List.of();
        }

        int size = rows.size();
        if (size <= MAX_CANDLE_POINTS) {
            return rows.stream().map(mapper).toList();
        }

        int step = (int) Math.ceil(size / (double) MAX_CANDLE_POINTS);
        List<TokenOhlcPayload> sampled = new ArrayList<>();
        for (int i = 0; i < size; i += step) {
            sampled.add(mapper.apply(rows.get(i)));
        }
        return sampled;
    }

    private List<Pair> findPairsForToken(String chainId, String tokenId, int maxLookups) {
        String prefix = tokenId + "-";
        List<PairTokenLookup> lookups = pairTokenLookupRepository.findByChainIdAndIdStartingWith(
                chainId,
                prefix,
                PageRequest.of(0, Math.max(1, Math.min(maxLookups, 1000)))
        );

        List<String> pairIds = lookups.stream()
                .map(PairTokenLookup::getPairId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (pairIds.isEmpty()) {
            return List.of();
        }

        return pairRepository.findByChainIdAndIdIn(chainId, pairIds);
    }

    private Map<String, Token> findTokenMetaMap(String chainId, List<String> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Map.of();
        }

        return tokenRepository.findByChainIdAndIdIn(chainId, tokenIds).stream()
                .collect(Collectors.toMap(Token::getId, t -> t));
    }

    private TokenLitePayload tokenLite(String address, Token token) {
        if (token == null) {
            return TokenLitePayload.builder()
                    .address(address)
                    .symbol("UNKNOWN")
                    .name(address)
                    .build();
        }

        return TokenLitePayload.builder()
                .address(token.getId())
                .symbol(token.getSymbol())
                .name(token.getName())
                .build();
    }

    private static class AggregatedOhlc {
        private final Integer timestamp;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volumeUsd;
        private BigDecimal tvlUsd;

        AggregatedOhlc(Integer timestamp) {
            this.timestamp = timestamp;
            this.open = null;
            this.high = null;
            this.low = null;
            this.close = null;
            this.volumeUsd = BigDecimal.ZERO;
            this.tvlUsd = BigDecimal.ZERO;
        }

        void add(TokenHourData row) {
            BigDecimal rowOpen = row.getOpen();
            BigDecimal rowHigh = row.getHigh();
            BigDecimal rowLow = row.getLow();
            BigDecimal rowClose = row.getClose();

            if (open == null) {
                open = rowOpen;
            }

            close = rowClose;

            if (high == null || (rowHigh != null && rowHigh.compareTo(high) > 0)) {
                high = rowHigh;
            }

            if (low == null || (rowLow != null && rowLow.compareTo(low) < 0)) {
                low = rowLow;
            }

            volumeUsd = volumeUsd.add(row.getVolumeUsd() == null ? BigDecimal.ZERO : row.getVolumeUsd());
            tvlUsd = row.getTotalValueLockedUsd() == null ? tvlUsd : row.getTotalValueLockedUsd();
        }

        TokenOhlcPayload toPayload() {
            return TokenOhlcPayload.builder()
                    .timestamp(timestamp)
                    .open(open == null ? BigDecimal.ZERO : open)
                    .high(high == null ? BigDecimal.ZERO : high)
                    .low(low == null ? BigDecimal.ZERO : low)
                    .close(close == null ? BigDecimal.ZERO : close)
                    .volumeUsd(volumeUsd)
                    .tvlUsd(tvlUsd == null ? BigDecimal.ZERO : tvlUsd)
                    .build();
        }
    }

    private TransactionPayload toPayload(String chainId, EventRow row) {
        return TransactionPayload.builder()
                .id(row.getId())
                .chainId(chainId)
                .blockNumber(row.getBlockNumber() == null ? 0L : row.getBlockNumber())
                .txHash(row.getTxHash())
                .eventSig(null)
                .decodedName(row.getKind())
                .decodedData(row.getDecodedData())
                .status("indexed")
                // Use unix timestamp (seconds) to avoid timezone/parsing issues on frontend.
                .createdAt(String.valueOf(row.getTimestamp()))
                .build();
    }

    private String normalizeChainId(String chainId) {
        if (chainId == null) return "sepolia";
        String v = chainId.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "11155111", "sepolia" -> "sepolia";
            case "534351", "scroll-sepolia", "scroll_sepolia", "scroll sepolia" -> "scroll-sepolia";
            default -> v;
        };
    }

    private String buildJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize decodedData json: {}", e.getMessage());
            return "{}";
        }
    }

    private BigDecimal safeBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> buildSwapData(Swap swap) {
        Map<String, Object> data = new HashMap<>();
        data.put("pair", swap.getPairId());
        data.put("sender", swap.getSender());
        data.put("from", swap.getFromAddress());
        data.put("to", swap.getToAddress());
        data.put("amountUsd", toPlainString(swap.getAmountUsd()));
        data.put("logIndex", swap.getLogIndex());
        data.put("timestamp", swap.getTimestamp());
        return data;
    }

    private Map<String, Object> buildSwapDecodedData(String chainId, Swap swap) {
        Map<String, Object> data = buildSwapData(swap);

        Pair pair = pairRepository.findByIdAndChainId(swap.getPairId(), chainId).orElse(null);
        if (pair == null) {
            data.put("tokenIn", null);
            data.put("tokenOut", null);
            data.put("amountIn", null);
            data.put("amountOut", null);
            return data;
        }

        Token token0 = tokenRepository.findByIdAndChainId(pair.getToken0Id(), chainId).orElse(null);
        Token token1 = tokenRepository.findByIdAndChainId(pair.getToken1Id(), chainId).orElse(null);

        boolean isToken0In = swap.getAmount0In() != null && swap.getAmount0In().compareTo(BigDecimal.ZERO) > 0;
        if (isToken0In) {
            data.put("tokenIn", tokenRef(pair.getToken0Id(), token0));
            data.put("tokenOut", tokenRef(pair.getToken1Id(), token1));
            data.put("amountIn", toPlainString(swap.getAmount0In()));
            data.put("amountOut", toPlainString(swap.getAmount1Out()));
        } else {
            data.put("tokenIn", tokenRef(pair.getToken1Id(), token1));
            data.put("tokenOut", tokenRef(pair.getToken0Id(), token0));
            data.put("amountIn", toPlainString(swap.getAmount1In()));
            data.put("amountOut", toPlainString(swap.getAmount0Out()));
        }

        // Uniswap interface "Wallet" column typically refers to the trader (originator).
        // In our Swap entity, `sender` can be a contract/router; `fromAddress` is closer to the user wallet.
        data.put("account", swap.getFromAddress());
        return data;
    }

    private Map<String, Object> tokenRef(String tokenId, Token token) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("id", tokenId);
        ref.put("symbol", token == null ? null : token.getSymbol());
        ref.put("name", token == null ? null : token.getName());
        ref.put("decimals", token == null ? null : token.getDecimals());
        return ref;
    }

    private Map<String, Object> buildMintData(Mint mint) {
        Map<String, Object> data = new HashMap<>();
        data.put("pair", mint.getPairId());
        data.put("to", mint.getToAddress());
        data.put("sender", mint.getSender());
        data.put("amountUsd", toPlainString(mint.getAmountUsd()));
        data.put("logIndex", mint.getLogIndex());
        data.put("timestamp", mint.getTimestamp());
        return data;
    }

    private Map<String, Object> buildBurnData(Burn burn) {
        Map<String, Object> data = new HashMap<>();
        data.put("pair", burn.getPairId());
        data.put("sender", burn.getSender());
        data.put("to", burn.getToAddress());
        data.put("amountUsd", toPlainString(burn.getAmountUsd()));
        data.put("logIndex", burn.getLogIndex());
        data.put("timestamp", burn.getTimestamp());
        return data;
    }

    private Map<String, Object> buildBridgeData(BridgeTransfer transfer) {
        Map<String, Object> data = new HashMap<>();
        data.put("messageId", transfer.getMessageId());
        data.put("sender", transfer.getSender());
        data.put("receiver", transfer.getReceiver());
        data.put("token", transfer.getToken());
        data.put("amount", toPlainString(transfer.getAmount()));
        data.put("ccipFee", toPlainString(transfer.getCcipFee()));
        data.put("payInLink", transfer.getPayInLink());
        data.put("timestamp", transfer.getTimestamp());
        return data;
    }

    private String toPlainString(BigDecimal value) {
        if (value == null) return null;
        return value.stripTrailingZeros().toPlainString();
    }

    @Value
    private static class EventRow {
        String id;
        String kind;
        String txHash;
        Long timestamp;
        Long blockNumber;
        Long logIndex;
        String decodedData;
        String createdAt;

        static EventRow fromSwap(Swap swap, Transaction tx, String decodedData) {
            return new EventRow(
                    "swap:" + swap.getId(),
                    "Swap",
                    swap.getTransactionId(),
                    swap.getTimestamp(),
                    tx == null ? 0L : tx.getBlockNumber(),
                    swap.getLogIndex(),
                    decodedData,
                    String.valueOf(swap.getTimestamp())
            );
        }

        static EventRow fromMint(Mint mint, Transaction tx, String decodedData) {
            return new EventRow(
                    "mint:" + mint.getId(),
                    "Mint",
                    mint.getTransactionId(),
                    mint.getTimestamp(),
                    tx == null ? 0L : tx.getBlockNumber(),
                    mint.getLogIndex(),
                    decodedData,
                    String.valueOf(mint.getTimestamp())
            );
        }

        static EventRow fromBurn(Burn burn, Transaction tx, String decodedData) {
            return new EventRow(
                    "burn:" + burn.getId(),
                    "Burn",
                    burn.getTransactionId(),
                    burn.getTimestamp(),
                    tx == null ? 0L : tx.getBlockNumber(),
                    burn.getLogIndex(),
                    decodedData,
                    String.valueOf(burn.getTimestamp())
            );
        }

        static EventRow fromBridge(BridgeTransfer transfer, String decodedData) {
            return new EventRow(
                    "bridge:" + transfer.getId(),
                    "Bridge",
                    transfer.getTxHash(),
                    transfer.getTimestamp(),
                    transfer.getBlockNumber(),
                    null,
                    decodedData,
                    String.valueOf(transfer.getTimestamp())
            );
        }
    }
}
