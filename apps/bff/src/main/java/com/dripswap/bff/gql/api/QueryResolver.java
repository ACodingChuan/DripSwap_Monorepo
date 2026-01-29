package com.dripswap.bff.gql.api;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.gql.dataloader.UnifiedDataLoaderRegistrar;
import com.dripswap.bff.gql.dto.ExplorePoolRowPayload;
import com.dripswap.bff.gql.dto.ExploreStatsSeed;
import com.dripswap.bff.gql.dto.ExploreTokenRowPayload;
import com.dripswap.bff.gql.dto.PoolDayWindowStats;
import com.dripswap.bff.gql.dto.PoolDetailsPayload;
import com.dripswap.bff.gql.dto.PoolCandleKey;
import com.dripswap.bff.gql.dto.PoolKey;
import com.dripswap.bff.gql.dto.PoolOhlc;
import com.dripswap.bff.gql.dto.PoolTransactionRowPayload;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenOhlc;
import com.dripswap.bff.gql.dto.TokenPoolRow;
import com.dripswap.bff.gql.dto.TokenDetailsPayload;
import com.dripswap.bff.gql.dto.TokenLitePayload;
import com.dripswap.bff.gql.dto.TokenTransactionRow;
import com.dripswap.bff.gql.dto.TransactionPayload;
import com.dripswap.bff.gql.enums.ExplorePoolSort;
import com.dripswap.bff.gql.enums.ExploreTokenSort;
import com.dripswap.bff.gql.enums.PoolChartInterval;
import com.dripswap.bff.gql.enums.TokenChartInterval;
import com.dripswap.bff.gql.enums.ExploreTxType;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import graphql.GraphqlErrorException;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dataloader.DataLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Controller
@RequiredArgsConstructor
public class QueryResolver {

    private static final long TTL_TOKEN_LIST_SECONDS = 60;
    private static final long TTL_POOL_LIST_SECONDS = 60;
    private static final long TTL_RECENT_TX_SECONDS = 30;
    private static final long TTL_TOKEN_DETAILS_SECONDS = 60;
    private static final long TTL_TOKEN_CANDLES_SECONDS = 60;
    private static final long TTL_POOL_DETAILS_SECONDS = 60;
    private static final long TTL_POOL_CANDLES_SECONDS = 60;
    private static final long TTL_POOL_TX_SECONDS = 30;

    private static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final SubgraphProperties subgraphProperties;
    private final SubgraphClient subgraphClient;

    /**
     * Explore protocol stats (6.2).
     *
     * <p>We return a lightweight seed object and resolve fields via {@link ExploreStatsFieldResolver}
     * + DataLoader (with Redis read-through caching).</p>
     */
    @QueryMapping
    public ExploreStatsSeed exploreStats(
            @Argument String chainId,
            @Argument Integer days,
            DataFetchingEnvironment env
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int windowDays = days == null ? 30 : Math.max(1, Math.min(days, 90));

        boolean needsSeries = env != null && (env.getSelectionSet().contains("tvlSeries") || env.getSelectionSet().contains("volumeSeries"));
        if (env != null) {
            env.getGraphQlContext().put("exploreStats:needsSeries:" + normalizedChainId + ":" + windowDays, needsSeries);
        }
        return new ExploreStatsSeed(normalizedChainId, windowDays);
    }

    /**
     * Explore tokens (6.3).
     *
     * <p>Main query stays lean: only returns base token list (id/symbol/name/decimals/totalSupply/derivedETH).
     * Derived fields are resolved via {@link ExploreTokenRowFieldResolver} + DataLoader (with Redis 2nd-level cache).</p>
     */
    @QueryMapping
    public List<ExploreTokenRowPayload> exploreTokens(
            @Argument String chainId,
            @Argument Integer limit,
            @Argument String search,
            @Argument ExploreTokenSort sort
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));

        // Redis key: ds:v2:{chain}:tokens:list:{limit}:{searchHash}
        String searchKey = (search == null || search.trim().isEmpty())
                ? "all"
                : Integer.toHexString(search.trim().toLowerCase(Locale.ROOT).hashCode());
        String redisKey = String.format("ds:v2:%s:tokens:list:%d:%s", normalizedChainId, size, searchKey);

        // 1) Try Redis first
        String cached = safeRedisGet(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                return objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExploreTokenRowPayload.class)
                );
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to subgraph", redisKey, e);
            }
        }

        // 2) Redis miss -> query Goldsky subgraph
        String endpoint = requireSubgraphEndpoint(normalizedChainId);

        // Fetch a larger page and filter in-memory (avoid complex OR filters on subgraph).
        int fetchSize = Math.min(500, Math.max(200, size * 5));
        String query = """
            query ExploreTokens($first: Int!) {
              tokens: tokens(first: $first, orderBy: tradeVolumeUSD, orderDirection: desc) {
                id
                symbol
                name
                decimals
                totalSupply
                derivedETH
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, query, Map.of("first", fetchSize));
        } catch (Exception e) {
            log.warn("exploreTokens subgraph failed: chainId={}, err={}", normalizedChainId, e.getMessage());
            return List.of();
        }

        JsonNode tokensNode = data == null ? null : data.get("tokens");
        if (tokensNode == null || !tokensNode.isArray()) {
            return List.of();
        }

        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<ExploreTokenRowPayload> result = new ArrayList<>();
        for (JsonNode n : tokensNode) {
            String id = n.hasNonNull("id") ? n.get("id").asText("") : "";
            if (id.isBlank()) continue;

            String symbol = n.hasNonNull("symbol") ? n.get("symbol").asText("") : "";
            String name = n.hasNonNull("name") ? n.get("name").asText("") : "";

            if (!q.isEmpty()) {
                String idLc = id.toLowerCase(Locale.ROOT);
                if (!idLc.contains(q)
                        && !symbol.toLowerCase(Locale.ROOT).contains(q)
                        && !name.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
            }

            Integer decimals = parseIntField(n, "decimals");
            BigDecimal totalSupply = parseBigDecimalField(n, "totalSupply");
            BigDecimal derivedEth = parseBigDecimalField(n, "derivedETH");

            result.add(ExploreTokenRowPayload.builder()
                    .id(id)
                    .chainId(normalizedChainId)
                    .symbol(symbol)
                    .name(name)
                    .decimals(decimals == null ? 0 : decimals)
                    .totalSupply(totalSupply == null ? BigDecimal.ZERO : totalSupply)
                    .derivedETH(derivedEth == null ? BigDecimal.ZERO : derivedEth)
                    .build());

            if (result.size() >= size) break;
        }

        if (result.isEmpty()) {
            return List.of();
        }

        // 3) Write base list back to Redis (derived fields are computed via field resolvers)
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json, java.time.Duration.ofSeconds(TTL_TOKEN_LIST_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to cache exploreTokens to Redis: {}", redisKey, e);
        }

        return result;
    }

    /**
     * Explore pools (6.4).
     *
     * <p>GraphQL-friendly implementation:
     * <ul>
     *   <li>Query returns a candidate base list (pair + token lites + tvlUsd) and caches it in Redis.</li>
     *   <li>Computed fields (volume/fees/tx/change/apr) are resolved via SchemaMapping + DataLoader.</li>
     *   <li>If sorting requires computed metrics, Query uses the same DataLoader to batch-load sort keys,
     *       so field resolvers can reuse the request-scoped DataLoader cache.</li>
     * </ul>
     * </p>
     */
    @QueryMapping
    public CompletableFuture<List<ExplorePoolRowPayload>> explorePools(
            @Argument String chainId,
            @Argument Integer limit,
            @Argument String search,
            @Argument ExplorePoolSort sort,
            DataFetchingEnvironment env
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        ExplorePoolSort normalizedSort = sort == null ? ExplorePoolSort.TVL_DESC : sort;

        String searchKey = (search == null || search.trim().isEmpty())
                ? "all"
                : Integer.toHexString(search.trim().toLowerCase(Locale.ROOT).hashCode());

        String redisKey = String.format("ds:v2:%s:explore:pools:base:%d:%s", normalizedChainId, size, searchKey);

        // 1) Redis first
        String cached = safeRedisGet(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                List<ExplorePoolRowPayload> base = objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExplorePoolRowPayload.class)
                );
                return sortExplorePools(base, normalizedSort, env, size);
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to subgraph", redisKey, e);
            }
        }

        String endpoint = requireSubgraphEndpoint(normalizedChainId);

        // Fetch a larger page and filter in-memory (keep subgraph query simple).
        int fetchSize = Math.min(250, Math.max(100, size * 5));
        String pairsQuery = """
            query ExplorePoolsPairs($first: Int!) {
              pairs: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc) {
                id
                reserveUSD
                token0 { id symbol name }
                token1 { id symbol name }
              }
            }
            """;

        JsonNode pairsData;
        try {
            pairsData = subgraphClient.query(endpoint, pairsQuery, Map.of("first", fetchSize));
        } catch (Exception e) {
            log.warn("explorePools pairs subgraph failed: chainId={}, err={}", normalizedChainId, e.getMessage());
            return CompletableFuture.completedFuture(List.of());
        }

        JsonNode pairsNode = pairsData == null ? null : pairsData.get("pairs");
        if (pairsNode == null || !pairsNode.isArray()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String[] pairQueryParts = splitPairQuery(q);

        List<ExplorePoolRowPayload> baseRows = new ArrayList<>();
        for (JsonNode n : pairsNode) {
            String pairAddress = n.hasNonNull("id") ? n.get("id").asText("") : "";
            if (pairAddress.isBlank()) continue;

            JsonNode t0 = n.get("token0");
            JsonNode t1 = n.get("token1");
            String t0Addr = t0 != null && t0.hasNonNull("id") ? t0.get("id").asText("") : "";
            String t1Addr = t1 != null && t1.hasNonNull("id") ? t1.get("id").asText("") : "";
            String t0Symbol = t0 != null && t0.hasNonNull("symbol") ? t0.get("symbol").asText("") : "";
            String t1Symbol = t1 != null && t1.hasNonNull("symbol") ? t1.get("symbol").asText("") : "";
            String t0Name = t0 != null && t0.hasNonNull("name") ? t0.get("name").asText("") : "";
            String t1Name = t1 != null && t1.hasNonNull("name") ? t1.get("name").asText("") : "";

            if (!q.isEmpty() && !matchesPoolSearch(q, pairQueryParts, pairAddress, t0Addr, t1Addr, t0Symbol, t1Symbol, t0Name, t1Name)) {
                continue;
            }

            BigDecimal tvlUsd = parseBigDecimalField(n, "reserveUSD");

            baseRows.add(ExplorePoolRowPayload.builder()
                    .pairAddress(pairAddress)
                    .chainId(normalizedChainId)
                    .token0(TokenLitePayload.builder().address(t0Addr).symbol(t0Symbol).name(t0Name).build())
                    .token1(TokenLitePayload.builder().address(t1Addr).symbol(t1Symbol).name(t1Name).build())
                    .tvlUsd(safeBigDecimal(tvlUsd))
                    // Computed fields are resolved via ExplorePoolRowFieldResolver + DataLoader.
                    .tvlChange1d(null)
                    .volume24hUsd(null)
                    .volume1wUsd(null)
                    .fees24hUsd(null)
                    .tx24hCount(null)
                    .apr(null)
                    .build());
        }

        if (baseRows.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // 3) Cache to Redis
        try {
            String json = objectMapper.writeValueAsString(baseRows);
            redisTemplate.opsForValue().set(redisKey, json, java.time.Duration.ofSeconds(TTL_POOL_LIST_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to cache explorePools to Redis: {}", redisKey, e);
        }

        return sortExplorePools(baseRows, normalizedSort, env, size);
    }

    /**
     * Explore recent transactions (6.5).
     *
     * <p>Back-compat: if types is omitted, we default to SWAP so existing front-end UI keeps working.</p>
     */
    @QueryMapping
    public List<TransactionPayload> recentTransactions(
            @Argument String chainId,
            @Argument Integer limit,
            @Argument List<ExploreTxType> types
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int size = limit == null ? 25 : Math.max(1, Math.min(limit, 100));

        // UI-side filtering: always return a mixed list of SWAP/MINT/BURN and let the frontend filter.
        // We keep the `types` argument in schema for contract stability, but ignore it here.
        String redisKey = String.format("ds:v2:%s:explore:tx:%d:all", normalizedChainId, size);

        String cached = safeRedisGet(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                return objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TransactionPayload.class)
                );
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to subgraph", redisKey, e);
            }
        }

        String endpoint = requireSubgraphEndpoint(normalizedChainId);

        int perType = Math.min(200, Math.max(50, size * 3));
        boolean needSwaps = true;
        boolean needMints = true;
        boolean needBurns = true;

        // Always include the three top-level lists so we don't hit "unused variable" validation errors
        // when building dynamic selection sets against subgraph GraphQL.
        String q = """
            query RecentTransactions($swaps: Int!, $mints: Int!, $burns: Int!) {
              swaps: swaps(first: $swaps, orderBy: timestamp, orderDirection: desc) {
                id
                timestamp
                amountUSD
                sender
                from
                to
                amount0In
                amount1In
                amount0Out
                amount1Out
                pair { id token0 { id symbol name decimals } token1 { id symbol name decimals } }
                transaction { id blockNumber }
              }
              mints: mints(first: $mints, orderBy: timestamp, orderDirection: desc) {
                id
                timestamp
                amountUSD
                sender
                to
                amount0
                amount1
                pair { id token0 { id symbol name decimals } token1 { id symbol name decimals } }
                transaction { id blockNumber }
              }
              burns: burns(first: $burns, orderBy: timestamp, orderDirection: desc) {
                id
                timestamp
                amountUSD
                sender
                to
                amount0
                amount1
                pair { id token0 { id symbol name decimals } token1 { id symbol name decimals } }
                transaction { id blockNumber }
              }
            }
            """;

        Map<String, Object> vars = new HashMap<>();
        vars.put("swaps", perType);
        vars.put("mints", perType);
        vars.put("burns", perType);

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, vars);
        } catch (Exception e) {
            log.warn("recentTransactions subgraph failed: chainId={}, err={}", normalizedChainId, e.getMessage());
            return List.of();
        }

        List<TxItem> items = new ArrayList<>();
        if (needSwaps) {
            JsonNode swaps = data == null ? null : data.get("swaps");
            if (swaps != null && swaps.isArray()) {
                for (JsonNode row : swaps) {
                    TxItem item = mapSwap(normalizedChainId, row);
                    if (item != null) items.add(item);
                }
            }
        }
        if (needMints) {
            JsonNode mints = data == null ? null : data.get("mints");
            if (mints != null && mints.isArray()) {
                for (JsonNode row : mints) {
                    TxItem item = mapMintBurn(normalizedChainId, ExploreTxType.MINT, row);
                    if (item != null) items.add(item);
                }
            }
        }
        if (needBurns) {
            JsonNode burns = data == null ? null : data.get("burns");
            if (burns != null && burns.isArray()) {
                for (JsonNode row : burns) {
                    TxItem item = mapMintBurn(normalizedChainId, ExploreTxType.BURN, row);
                    if (item != null) items.add(item);
                }
            }
        }

        items.sort((a, b) -> {
            int ts = Long.compare(b.timestamp, a.timestamp);
            if (ts != 0) return ts;
            int bn = Long.compare(b.blockNumber, a.blockNumber);
            if (bn != 0) return bn;
            return a.id.compareTo(b.id);
        });

        List<TransactionPayload> out = new ArrayList<>();
        for (TxItem item : items) {
            out.add(TransactionPayload.builder()
                    .id(item.id)
                    .chainId(normalizedChainId)
                    .blockNumber(item.blockNumber)
                    .txHash(item.txHash)
                    .decodedName(item.decodedName)
                    .decodedData(item.decodedDataJson)
                    .status("CONFIRMED")
                    .createdAt(Long.toString(item.timestamp))
                    .build());
            if (out.size() >= size) break;
        }

        try {
            String json = objectMapper.writeValueAsString(out);
            redisTemplate.opsForValue().set(redisKey, json, java.time.Duration.ofSeconds(TTL_RECENT_TX_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to cache recentTransactions to Redis: {}", redisKey, e);
        }

        return out;
    }

    /**
     * Token details (6.6).
     *
     * <p>Query returns a base payload (symbol/name/decimals/derivedETH/totalSupply...) and caches it in Redis.
     * Computed fields are resolved via {@link TokenDetailsFieldResolver} + DataLoader.</p>
     */
    @QueryMapping
    public TokenDetailsPayload tokenDetails(
            @Argument String chainId,
            @Argument String tokenAddress
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        if (tokenAddress == null || tokenAddress.trim().isEmpty()) return null;
        String token = tokenAddress.trim().toLowerCase(Locale.ROOT);

        String redisKey = String.format("ds:v2:%s:token:%s:detailsBase", normalizedChainId, token);
        String cached = safeRedisGet(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                return objectMapper.readValue(cached, TokenDetailsPayload.class);
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to subgraph", redisKey, e);
            }
        }

        String endpoint = requireSubgraphEndpoint(normalizedChainId);
        String q = """
            query TokenDetailsBase($token: Bytes!) {
              tokens(first: 1, where: { id: $token }) {
                id
                symbol
                name
                decimals
                totalSupply
                derivedETH
                totalLiquidity
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("token", token));
        } catch (Exception e) {
            log.warn("tokenDetails base subgraph failed: chainId={}, err={}", normalizedChainId, e.getMessage());
            return null;
        }

        JsonNode arr = data == null ? null : data.get("tokens");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        JsonNode t = arr.get(0);

        String id = t.hasNonNull("id") ? t.get("id").asText("") : "";
        if (id.isBlank()) return null;

        Integer decimals = parseIntField(t, "decimals");
        BigDecimal totalSupply = parseBigDecimalField(t, "totalSupply");
        BigDecimal derivedEth = parseBigDecimalField(t, "derivedETH");
        BigDecimal totalLiquidity = parseBigDecimalField(t, "totalLiquidity");

        TokenDetailsPayload payload = TokenDetailsPayload.builder()
                .chainId(normalizedChainId)
                .address(id)
                .symbol(t.hasNonNull("symbol") ? t.get("symbol").asText("") : "")
                .name(t.hasNonNull("name") ? t.get("name").asText("") : "")
                .decimals(decimals == null ? 0 : decimals)
                .totalSupply(totalSupply)
                .derivedETH(derivedEth)
                .totalLiquidity(totalLiquidity)
                // Computed via field resolvers
                .priceUsd(null)
                .change24hPct(null)
                .tvlUsd(null)
                .volume24hUsd(null)
                .fdvUsd(null)
                .build();

        try {
            redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(payload), java.time.Duration.ofSeconds(TTL_TOKEN_DETAILS_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to cache tokenDetails base to Redis: {}", redisKey, e);
        }

        return payload;
    }

    /**
     * Pool details (6.9).
     *
     * <p>Base pool metadata + reserves come from the Pair entity (subgraph, cached via DataLoader + Redis).
     * 24h stats reuse the same day-window loader used by Explore Pools.</p>
     */
    @QueryMapping
    public CompletableFuture<PoolDetailsPayload> poolDetails(
            @Argument String chainId,
            @Argument String pairAddress,
            DataFetchingEnvironment env
    ) {
        if (pairAddress == null || pairAddress.isBlank() || env == null) {
            return CompletableFuture.completedFuture(null);
        }
        String normalizedChainId = normalizeChainId(chainId);
        String pair = pairAddress.trim().toLowerCase(Locale.ROOT);

        DataLoader<PoolKey, PoolDetailsPayload> loader =
                env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_DETAILS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }
        return loader.load(new PoolKey(normalizedChainId, pair));
    }

    /**
     * Pool candles (6.10).
     *
     * <p>Backed by time aggregation entities (PairHourData/PairDayData).</p>
     */
    @QueryMapping
    public CompletableFuture<List<PoolOhlc>> poolPriceCandles(
            @Argument String chainId,
            @Argument String pairAddress,
            @Argument PoolChartInterval interval,
            @Argument Integer from,
            @Argument Integer to,
            DataFetchingEnvironment env
    ) {
        if (pairAddress == null || pairAddress.isBlank() || interval == null || env == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        String normalizedChainId = normalizeChainId(chainId);
        String pair = pairAddress.trim().toLowerCase(Locale.ROOT);

        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        int toSec = to == null ? nowSec : Math.min(to, nowSec);
        int fromSec = from == null ? (toSec - 86_400) : from;
        if (toSec <= 0 || fromSec <= 0 || toSec <= fromSec) {
            return CompletableFuture.completedFuture(List.of());
        }

        int bucketSize = interval == PoolChartInterval.HOUR ? 3600 : 86_400;
        int toBucket = toSec - (toSec % bucketSize);

        DataLoader<PoolCandleKey, List<PoolOhlc>> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_CANDLES);
        if (loader == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return loader.load(new PoolCandleKey(normalizedChainId, pair, interval, toBucket))
                .thenApply(window -> {
                    if (window == null || window.isEmpty()) return List.of();
                    final int f = fromSec;
                    final int t = toSec;
                    return window.stream()
                            .filter(c -> c != null && c.getTimestamp() != null)
                            .filter(c -> c.getTimestamp() >= f && c.getTimestamp() <= t)
                            .toList();
                });
    }

    /**
     * Pool transactions (6.10).
     */
    @QueryMapping
    public CompletableFuture<List<PoolTransactionRowPayload>> poolTransactions(
            @Argument String chainId,
            @Argument String pairAddress,
            @Argument Integer limit,
            @Argument List<ExploreTxType> types,
            DataFetchingEnvironment env
    ) {
        if (pairAddress == null || pairAddress.isBlank() || env == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        String normalizedChainId = normalizeChainId(chainId);
        String pair = pairAddress.trim().toLowerCase(Locale.ROOT);
        int size = limit == null ? 25 : Math.max(1, Math.min(limit, 100));

        DataLoader<PoolKey, List<PoolTransactionRowPayload>> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_TRANSACTIONS);
        if (loader == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return loader.load(new PoolKey(normalizedChainId, pair))
                .thenApply(rows -> {
                    if (rows == null || rows.isEmpty()) return List.of();
                    List<PoolTransactionRowPayload> filtered = rows;
                    if (types != null && !types.isEmpty()) {
                        filtered = rows.stream()
                                .filter(r -> r != null && r.getType() != null && types.contains(r.getType()))
                                .toList();
                    }
                    if (filtered.size() <= size) return filtered;
                    return filtered.subList(0, size);
                });
    }

    /**
     * Token pools (6.8).
     */
    @QueryMapping
    public CompletableFuture<List<TokenPoolRow>> tokenPools(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument Integer limit,
            DataFetchingEnvironment env
    ) {
        if (tokenAddress == null || tokenAddress.isBlank() || env == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        String normalizedChainId = normalizeChainId(chainId);
        String token = tokenAddress.trim().toLowerCase(Locale.ROOT);
        int size = limit == null ? 10 : Math.max(1, Math.min(limit, 50));

        DataLoader<TokenKey, List<TokenPoolRow>> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_POOLS);
        if (loader == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return loader.load(new TokenKey(normalizedChainId, token))
                .thenApply(rows -> {
                    if (rows == null || rows.isEmpty()) return List.of();
                    if (rows.size() <= size) return rows;
                    return rows.subList(0, size);
                });
    }

    /**
     * Token transactions (6.8).
     */
    @QueryMapping
    public CompletableFuture<List<TokenTransactionRow>> tokenTransactions(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument Integer limit,
            DataFetchingEnvironment env
    ) {
        if (tokenAddress == null || tokenAddress.isBlank() || env == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        String normalizedChainId = normalizeChainId(chainId);
        String token = tokenAddress.trim().toLowerCase(Locale.ROOT);
        int size = limit == null ? 25 : Math.max(1, Math.min(limit, 100));

        DataLoader<TokenKey, List<TokenTransactionRow>> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_TRANSACTIONS);
        if (loader == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return loader.load(new TokenKey(normalizedChainId, token))
                .thenApply(rows -> {
                    if (rows == null || rows.isEmpty()) return List.of();
                    if (rows.size() <= size) return rows;
                    return rows.subList(0, size);
                });
    }

    /**
     * Token price candles (6.6).
     *
     * <p>Currently returns an empty list when OHLC buckets are not available yet.</p>
     */
    @QueryMapping
    public List<TokenOhlc> tokenPriceCandles(
            @Argument String chainId,
            @Argument String tokenAddress,
            @Argument TokenChartInterval interval,
            @Argument Integer from,
            @Argument Integer to
    ) {
        if (tokenAddress == null || tokenAddress.isBlank()) return List.of();
        if (interval == null) return List.of();

        String normalizedChainId = normalizeChainId(chainId);
        String endpoint = requireSubgraphEndpoint(normalizedChainId);

        String token = tokenAddress.trim().toLowerCase(Locale.ROOT);
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        int toSec = to == null ? nowSec : Math.min(to, nowSec);
        int fromSec = from == null ? (toSec - 86_400) : from;
        if (toSec <= 0 || fromSec <= 0 || toSec <= fromSec) return List.of();

        // Cache the "latest window" per (chain, token, interval) and slice by [from,to].
        String redisKey = String.format("ds:v2:%s:token:%s:candles:%s", normalizedChainId, token, interval.name());
        List<TokenOhlc> window = null;
        try {
            String cached = safeRedisGet(redisKey);
            if (cached != null && !cached.isBlank()) {
                window = objectMapper.readValue(cached, new TypeReference<List<TokenOhlc>>() {});
            }
        } catch (Exception ignore) {
            window = null;
        }

        if (window == null) {
            window = fetchTokenCandleWindow(endpoint, token, interval, toSec);
            try {
                redisTemplate.opsForValue().set(
                        redisKey,
                        objectMapper.writeValueAsString(window),
                        java.time.Duration.ofSeconds(TTL_TOKEN_CANDLES_SECONDS)
                );
            } catch (Exception e) {
                log.debug("Failed to cache tokenPriceCandles window: key={}, err={}", redisKey, e.getMessage());
            }
        }

        if (window == null || window.isEmpty()) return List.of();
        final int f = fromSec;
        final int t = toSec;
        return window.stream()
                .filter(c -> c != null && c.getTimestamp() != null)
                .filter(c -> c.getTimestamp() >= f && c.getTimestamp() <= t)
                .toList();
    }

    private List<TokenOhlc> fetchTokenCandleWindow(
            String endpoint,
            String token,
            TokenChartInterval interval,
            int toSec
    ) {
        try {
            return switch (interval) {
                case MINUTE -> fetchMinuteCandles(endpoint, token, toSec);
                case HOUR -> fetchHourCandles(endpoint, token, toSec);
                case DAY -> fetchDayCandles(endpoint, token, toSec);
            };
        } catch (Exception e) {
            log.warn("tokenPriceCandles fetch failed: token={}, interval={}, err={}", token, interval, e.getMessage());
            return List.of();
        }
    }

    // Minute data is retained ~28h on Goldsky.
    private List<TokenOhlc> fetchMinuteCandles(String endpoint, String token, int toSec) {
        int windowFrom = Math.max(0, toSec - (28 * 3600));
        windowFrom = windowFrom - (windowFrom % 60);

        String q = """
            query TokenMinuteCandles($token: Bytes!, $from: Int!, $to: Int!, $first: Int!) {
              rows: tokenMinuteDatas(
                first: $first
                orderBy: periodStartUnix
                orderDirection: asc
                where: { token: $token, periodStartUnix_gte: $from, periodStartUnix_lte: $to }
              ) {
                periodStartUnix
                open
                high
                low
                close
                volumeUSD
                totalValueLockedUSD
              }
            }
            """;

        JsonNode data = subgraphClient.query(endpoint, q, Map.of(
                "token", token,
                "from", windowFrom,
                "to", toSec,
                "first", 1000
        ));

        JsonNode rows = data == null ? null : data.get("rows");
        if (rows == null || !rows.isArray()) return List.of();

        List<TokenOhlc> out = new ArrayList<>();
        for (JsonNode n : rows) {
            Integer ts = SubgraphJson.intValue(n, "periodStartUnix");
            if (ts == null) continue;
            out.add(TokenOhlc.builder()
                    .timestamp(ts)
                    .open(SubgraphJson.bigDecimal(n, "open"))
                    .high(SubgraphJson.bigDecimal(n, "high"))
                    .low(SubgraphJson.bigDecimal(n, "low"))
                    .close(SubgraphJson.bigDecimal(n, "close"))
                    .volumeUsd(SubgraphJson.bigDecimal(n, "volumeUSD"))
                    .tvlUsd(SubgraphJson.bigDecimal(n, "totalValueLockedUSD"))
                    .build());
        }
        return out;
    }

    // Hour data is retained ~32d on Goldsky.
    private List<TokenOhlc> fetchHourCandles(String endpoint, String token, int toSec) {
        int windowFrom = Math.max(0, toSec - (32 * 86_400));
        windowFrom = windowFrom - (windowFrom % 3600);

        String q = """
            query TokenHourCandles($token: Bytes!, $from: Int!, $to: Int!, $first: Int!) {
              rows: tokenHourDatas(
                first: $first
                orderBy: periodStartUnix
                orderDirection: asc
                where: { token: $token, periodStartUnix_gte: $from, periodStartUnix_lte: $to }
              ) {
                periodStartUnix
                open
                high
                low
                close
                volumeUSD
                totalValueLockedUSD
              }
            }
            """;

        JsonNode data = subgraphClient.query(endpoint, q, Map.of(
                "token", token,
                "from", windowFrom,
                "to", toSec,
                "first", 1000
        ));

        JsonNode rows = data == null ? null : data.get("rows");
        if (rows == null || !rows.isArray()) return List.of();

        List<TokenOhlc> out = new ArrayList<>();
        for (JsonNode n : rows) {
            Integer ts = SubgraphJson.intValue(n, "periodStartUnix");
            if (ts == null) continue;
            out.add(TokenOhlc.builder()
                    .timestamp(ts)
                    .open(SubgraphJson.bigDecimal(n, "open"))
                    .high(SubgraphJson.bigDecimal(n, "high"))
                    .low(SubgraphJson.bigDecimal(n, "low"))
                    .close(SubgraphJson.bigDecimal(n, "close"))
                    .volumeUsd(SubgraphJson.bigDecimal(n, "volumeUSD"))
                    .tvlUsd(SubgraphJson.bigDecimal(n, "totalValueLockedUSD"))
                    .build());
        }
        return out;
    }

    // Day data is retained long-term; we cache ~400d window to cover the UI's 1Y view.
    private List<TokenOhlc> fetchDayCandles(String endpoint, String token, int toSec) {
        int toDayStart = toSec - (toSec % 86_400);
        int windowFrom = Math.max(0, toDayStart - (400 * 86_400));
        windowFrom = windowFrom - (windowFrom % 86_400);

        // Fetch one extra day for "open" approximation.
        int fromWithPrev = Math.max(0, windowFrom - 86_400);

        String q = """
            query TokenDayCandles($token: Bytes!, $from: Int!, $to: Int!, $first: Int!) {
              rows: tokenDayDatas(
                first: $first
                orderBy: date
                orderDirection: asc
                where: { token: $token, date_gte: $from, date_lte: $to }
              ) {
                date
                priceUSD
                dailyVolumeUSD
                totalLiquidityUSD
              }
            }
            """;

        JsonNode data = subgraphClient.query(endpoint, q, Map.of(
                "token", token,
                "from", fromWithPrev,
                "to", toDayStart,
                "first", 1000
        ));

        JsonNode rows = data == null ? null : data.get("rows");
        if (rows == null || !rows.isArray()) return List.of();

        List<TokenOhlc> out = new ArrayList<>();
        BigDecimal prevClose = null;
        for (JsonNode n : rows) {
            Integer ts = SubgraphJson.intValue(n, "date");
            if (ts == null) continue;

            BigDecimal close = SubgraphJson.bigDecimal(n, "priceUSD");
            BigDecimal open = prevClose == null ? close : prevClose;
            BigDecimal high = open.max(close);
            BigDecimal low = open.min(close);

            out.add(TokenOhlc.builder()
                    .timestamp(ts)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volumeUsd(SubgraphJson.bigDecimal(n, "dailyVolumeUSD"))
                    .tvlUsd(SubgraphJson.bigDecimal(n, "totalLiquidityUSD"))
                    .build());

            prevClose = close;
        }

        // Drop the "prev day" row if present (it is always the first element when included).
        if (!out.isEmpty() && out.get(0).getTimestamp() != null && out.get(0).getTimestamp() < windowFrom) {
            out = out.subList(1, out.size());
        }
        return out;
    }

    private String safeRedisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis GET failed: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    private static BigDecimal safeBigDecimal(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String[] splitPairQuery(String q) {
        if (q == null) return null;
        String s = q.trim();
        if (!s.contains("/")) return null;
        String[] parts = s.split("/", 3);
        if (parts.length != 2) return null;
        String a = parts[0].trim();
        String b = parts[1].trim();
        if (a.isEmpty() || b.isEmpty()) return null;
        return new String[]{a, b};
    }

    private static boolean matchesPoolSearch(
            String q,
            String[] pairParts,
            String pairAddress,
            String t0Addr,
            String t1Addr,
            String t0Symbol,
            String t1Symbol,
            String t0Name,
            String t1Name
    ) {
        String pairLc = pairAddress == null ? "" : pairAddress.toLowerCase(Locale.ROOT);
        String t0AddrLc = t0Addr == null ? "" : t0Addr.toLowerCase(Locale.ROOT);
        String t1AddrLc = t1Addr == null ? "" : t1Addr.toLowerCase(Locale.ROOT);
        String t0SymbolLc = t0Symbol == null ? "" : t0Symbol.toLowerCase(Locale.ROOT);
        String t1SymbolLc = t1Symbol == null ? "" : t1Symbol.toLowerCase(Locale.ROOT);
        String t0NameLc = t0Name == null ? "" : t0Name.toLowerCase(Locale.ROOT);
        String t1NameLc = t1Name == null ? "" : t1Name.toLowerCase(Locale.ROOT);

        // Match "TOKEN0/TOKEN1" (order-agnostic) by symbol first.
        if (pairParts != null) {
            String a = pairParts[0];
            String b = pairParts[1];
            boolean direct = t0SymbolLc.equals(a) && t1SymbolLc.equals(b);
            boolean flipped = t0SymbolLc.equals(b) && t1SymbolLc.equals(a);
            if (direct || flipped) return true;
        }

        return pairLc.contains(q)
                || t0AddrLc.contains(q)
                || t1AddrLc.contains(q)
                || t0SymbolLc.contains(q)
                || t1SymbolLc.contains(q)
                || t0NameLc.contains(q)
                || t1NameLc.contains(q);
    }

    private CompletableFuture<List<ExplorePoolRowPayload>> sortExplorePools(
            List<ExplorePoolRowPayload> base,
            ExplorePoolSort sort,
            DataFetchingEnvironment env,
            int size
    ) {
        if (base == null || base.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // Always safe-sort by TVL without calling upstream.
        if (sort == null || sort == ExplorePoolSort.TVL_DESC) {
            List<ExplorePoolRowPayload> copy = new ArrayList<>(base);
            copy.sort(Comparator.comparing((ExplorePoolRowPayload r) -> safeBigDecimal(r.getTvlUsd())).reversed());
            if (copy.size() > size) copy = copy.subList(0, size);
            return CompletableFuture.completedFuture(copy);
        }

        if (env == null) {
            return sortExplorePools(base, ExplorePoolSort.TVL_DESC, null, size);
        }

        DataLoader<PoolKey, PoolDayWindowStats> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_DAY_WINDOW_STATS);
        if (loader == null) {
            return sortExplorePools(base, ExplorePoolSort.TVL_DESC, null, size);
        }

        List<PoolKey> keys = base.stream()
                .map(r -> new PoolKey(r.getChainId(), r.getPairAddress()))
                .toList();

        return loader.loadMany(keys).thenApply(statsList -> {
            Map<String, PoolDayWindowStats> statsByPair = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                PoolKey k = keys.get(i);
                PoolDayWindowStats s = (statsList != null && statsList.size() > i) ? statsList.get(i) : null;
                if (k == null || k.getPairAddress() == null) continue;
                statsByPair.put(k.getPairAddress().toLowerCase(Locale.ROOT), s);
            }

            Comparator<ExplorePoolRowPayload> byTvl = Comparator.comparing(
                    (ExplorePoolRowPayload r) -> safeBigDecimal(r.getTvlUsd())
            ).reversed();

            Comparator<ExplorePoolRowPayload> comparator = switch (sort) {
                case VOLUME_24H_DESC -> Comparator.comparing((ExplorePoolRowPayload r) -> {
                    PoolDayWindowStats s = statsByPair.get(r.getPairAddress().toLowerCase(Locale.ROOT));
                    return s == null ? BigDecimal.ZERO : safeBigDecimal(s.getVolume24hUsd());
                }).reversed().thenComparing(byTvl);
                case TX_24H_DESC -> Comparator.comparing((ExplorePoolRowPayload r) -> {
                    PoolDayWindowStats s = statsByPair.get(r.getPairAddress().toLowerCase(Locale.ROOT));
                    return s == null || s.getTx24hCount() == null ? -1 : s.getTx24hCount();
                }).reversed().thenComparing(byTvl);
                case FEES_24H_DESC -> Comparator.comparing((ExplorePoolRowPayload r) -> {
                    PoolDayWindowStats s = statsByPair.get(r.getPairAddress().toLowerCase(Locale.ROOT));
                    BigDecimal v = s == null ? BigDecimal.ZERO : safeBigDecimal(s.getVolume24hUsd());
                    return v.multiply(new BigDecimal("0.003"));
                }).reversed().thenComparing(byTvl);
                case APR_DESC -> Comparator.comparing((ExplorePoolRowPayload r) -> {
                    PoolDayWindowStats s = statsByPair.get(r.getPairAddress().toLowerCase(Locale.ROOT));
                    BigDecimal tvl = safeBigDecimal(r.getTvlUsd());
                    if (tvl.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.valueOf(-1);
                    BigDecimal v = s == null ? BigDecimal.ZERO : safeBigDecimal(s.getVolume24hUsd());
                    BigDecimal fees = v.multiply(new BigDecimal("0.003"));
                    if (fees.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.valueOf(-1);
                    return fees.multiply(new BigDecimal("365")).divide(tvl, 8, RoundingMode.HALF_UP);
                }).reversed().thenComparing(byTvl);
                case TVL_DESC -> byTvl;
            };

            List<ExplorePoolRowPayload> copy = new ArrayList<>(base);
            copy.sort(comparator);
            if (copy.size() > size) copy = copy.subList(0, size);
            return copy;
        }).toCompletableFuture();
    }

    private TxItem mapSwap(String chainId, JsonNode row) {
        if (row == null) return null;
        String id = row.hasNonNull("id") ? row.get("id").asText("") : "";
        Integer ts = row.hasNonNull("timestamp") ? parseIntField(row, "timestamp") : null;
        JsonNode tx = row.get("transaction");
        String txHash = tx != null && tx.hasNonNull("id") ? tx.get("id").asText("") : "";
        Long blockNumber = tx != null && tx.hasNonNull("blockNumber") ? parseBigIntAsLong(tx.get("blockNumber")) : 0L;
        JsonNode pair = row.get("pair");
        String pairAddr = pair != null && pair.hasNonNull("id") ? pair.get("id").asText("") : "";
        if (id.isBlank() || ts == null || txHash.isBlank() || pairAddr.isBlank()) return null;

        JsonNode token0 = pair.get("token0");
        JsonNode token1 = pair.get("token1");

        BigDecimal amount0In = parseBigDecimalField(row, "amount0In");
        BigDecimal amount1In = parseBigDecimalField(row, "amount1In");
        BigDecimal amount0Out = parseBigDecimalField(row, "amount0Out");
        BigDecimal amount1Out = parseBigDecimalField(row, "amount1Out");

        boolean token0In = amount0In.compareTo(BigDecimal.ZERO) > 0;
        JsonNode tokenIn = token0In ? token0 : token1;
        JsonNode tokenOut = token0In ? token1 : token0;

        String amountIn = token0In ? amount0In.toPlainString() : amount1In.toPlainString();
        String amountOut = token0In ? amount1Out.toPlainString() : amount0Out.toPlainString();
        String amountUsd = parseBigDecimalField(row, "amountUSD").toPlainString();

        String sender = row.hasNonNull("sender") ? row.get("sender").asText("") : "";
        String from = row.hasNonNull("from") ? row.get("from").asText("") : "";
        String to = row.hasNonNull("to") ? row.get("to").asText("") : "";

        Map<String, Object> decoded = new HashMap<>();
        decoded.put("type", "SWAP");
        decoded.put("timestamp", ts);
        decoded.put("pair", pairAddr);
        decoded.put("account", from.isBlank() ? sender : from);
        decoded.put("tokenIn", tokenJson(tokenIn));
        decoded.put("tokenOut", tokenJson(tokenOut));
        decoded.put("amountIn", amountIn);
        decoded.put("amountOut", amountOut);
        decoded.put("amountUsd", amountUsd);
        decoded.put("sender", sender);
        decoded.put("from", from);
        decoded.put("to", to);

        String decodedJson;
        try {
            decodedJson = objectMapper.writeValueAsString(decoded);
        } catch (Exception e) {
            decodedJson = null;
        }

        return new TxItem(id, txHash, blockNumber, ts.longValue(), "Swap", decodedJson);
    }

    private TxItem mapMintBurn(String chainId, ExploreTxType type, JsonNode row) {
        if (row == null || type == null) return null;
        String id = row.hasNonNull("id") ? row.get("id").asText("") : "";
        Integer ts = row.hasNonNull("timestamp") ? parseIntField(row, "timestamp") : null;
        JsonNode tx = row.get("transaction");
        String txHash = tx != null && tx.hasNonNull("id") ? tx.get("id").asText("") : "";
        Long blockNumber = tx != null && tx.hasNonNull("blockNumber") ? parseBigIntAsLong(tx.get("blockNumber")) : 0L;
        JsonNode pair = row.get("pair");
        String pairAddr = pair != null && pair.hasNonNull("id") ? pair.get("id").asText("") : "";
        if (id.isBlank() || ts == null || txHash.isBlank() || pairAddr.isBlank()) return null;

        JsonNode token0 = pair.get("token0");
        JsonNode token1 = pair.get("token1");

        String amount0 = parseBigDecimalField(row, "amount0").toPlainString();
        String amount1 = parseBigDecimalField(row, "amount1").toPlainString();
        String amountUsd = parseBigDecimalField(row, "amountUSD").toPlainString();

        String sender = row.hasNonNull("sender") ? row.get("sender").asText("") : "";
        String to = row.hasNonNull("to") ? row.get("to").asText("") : "";

        Map<String, Object> decoded = new HashMap<>();
        decoded.put("type", type.name());
        decoded.put("timestamp", ts);
        decoded.put("pair", pairAddr);
        decoded.put("account", !to.isBlank() ? to : sender);
        decoded.put("token0", tokenJson(token0));
        decoded.put("token1", tokenJson(token1));
        decoded.put("amount0", amount0);
        decoded.put("amount1", amount1);
        decoded.put("amountUsd", amountUsd);
        decoded.put("sender", sender);
        decoded.put("to", to);

        String decodedJson;
        try {
            decodedJson = objectMapper.writeValueAsString(decoded);
        } catch (Exception e) {
            decodedJson = null;
        }

        String decodedName = switch (type) {
            case MINT -> "Mint";
            case BURN -> "Burn";
            default -> null;
        };
        return new TxItem(id, txHash, blockNumber, ts.longValue(), decodedName, decodedJson);
    }

    private Map<String, Object> tokenJson(JsonNode token) {
        if (token == null || token.isNull()) return null;
        Map<String, Object> out = new HashMap<>();
        out.put("id", token.hasNonNull("id") ? token.get("id").asText("") : "");
        out.put("symbol", token.hasNonNull("symbol") ? token.get("symbol").asText("") : "");
        out.put("name", token.hasNonNull("name") ? token.get("name").asText("") : "");
        // Subgraph decimals is BigInt; encode as number to match frontend expectations.
        JsonNode dec = token.get("decimals");
        Integer decimals = dec == null || dec.isNull() ? null : parseBigIntAsInt(dec);
        out.put("decimals", decimals == null ? 0 : decimals);
        return out;
    }

    private Long parseBigIntAsLong(JsonNode v) {
        if (v == null || v.isNull()) return 0L;
        try {
            if (v.isLong()) return v.longValue();
            if (v.isInt()) return (long) v.intValue();
            if (v.isNumber()) return v.numberValue().longValue();
            String s = v.asText(null);
            if (s == null || s.isBlank()) return 0L;
            return new java.math.BigInteger(s).longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private Integer parseBigIntAsInt(JsonNode v) {
        if (v == null || v.isNull()) return null;
        try {
            if (v.isInt()) return v.intValue();
            if (v.isNumber()) return v.numberValue().intValue();
            String s = v.asText(null);
            if (s == null || s.isBlank()) return null;
            return new java.math.BigInteger(s).intValue();
        } catch (Exception e) {
            return null;
        }
    }

    private record TxItem(String id, String txHash, Long blockNumber, long timestamp, String decodedName, String decodedDataJson) {
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

    private String resolveSubgraphEndpoint(String normalizedChainId) {
        if (subgraphProperties == null || subgraphProperties.getChains() == null) return null;
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (chain == null) continue;
            if (!Objects.equals(chain.getId(), normalizedChainId)) continue;
            if (!chain.isEnabled()) continue;
            return chain.getEndpointV2();
        }
        return null;
    }

    private String requireSubgraphEndpoint(String normalizedChainId) {
        String endpoint = resolveSubgraphEndpoint(normalizedChainId);
        if (endpoint == null || endpoint.isBlank()) {
            throw GraphqlErrorException.newErrorException()
                    .message("Unsupported chainId: " + normalizedChainId)
                    .errorClassification(ErrorType.BAD_REQUEST)
                    .build();
        }
        return endpoint;
    }

    private BigDecimal parseBigDecimalField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return BigDecimal.ZERO;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return BigDecimal.ZERO;
        try {
            if (v.isNumber()) return v.decimalValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(text);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Integer parseIntField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return null;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return null;
        try {
            if (v.isInt()) return v.intValue();
            if (v.isNumber()) return v.numberValue().intValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return null;
            return new java.math.BigInteger(text).intValue();
        } catch (Exception e) {
            return null;
        }
    }
}
