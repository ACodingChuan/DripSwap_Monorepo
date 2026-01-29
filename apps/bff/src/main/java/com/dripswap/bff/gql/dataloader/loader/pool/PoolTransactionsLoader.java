package com.dripswap.bff.gql.dataloader.loader.pool;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.dto.PoolKey;
import com.dripswap.bff.gql.dto.PoolTransactionRowPayload;
import com.dripswap.bff.gql.enums.ExploreTxType;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PoolTransactionsLoader extends AbstractRedisSubgraphBatchLoader<PoolKey, Void, List<PoolTransactionRowPayload>> {

    private static final long TTL_SECONDS = 30;
    private static final int MAX_TX = 200;

    private final RedisCacheSupport redis;

    public PoolTransactionsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(PoolKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, PoolKey key) {
        if (key == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        String pair = key.getPairAddress() == null ? "" : key.getPairAddress().trim().toLowerCase(Locale.ROOT);
        if (pair.isBlank()) return null;
        return new CacheSpec(pair, RedisKeys.poolTransactions(cid, pair));
    }

    @Override
    protected Map<String, List<PoolTransactionRowPayload>> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJsonList(ids, redisKeyFn, PoolTransactionRowPayload.class);
    }

    @Override
    protected void redisSet(String redisKey, List<PoolTransactionRowPayload> value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, List<PoolTransactionRowPayload>> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, List<PoolTransactionRowPayload>> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        for (String pair : missIds) {
            if (pair == null || pair.isBlank()) continue;
            out.put(pair.toLowerCase(Locale.ROOT), fetchPoolTx(endpoint, pair));
        }
        return out;
    }

    private List<PoolTransactionRowPayload> fetchPoolTx(String endpoint, String pair) {
        String q = """
            query PoolTransactions($pair: Bytes!, $first: Int!) {
              swaps: swaps(first: $first, orderBy: timestamp, orderDirection: desc, where: { pair: $pair }) {
                timestamp
                amountUSD
                sender
                from
                to
                amount0In
                amount1In
                amount0Out
                amount1Out
                transaction { id }
              }
              mints: mints(first: $first, orderBy: timestamp, orderDirection: desc, where: { pair: $pair }) {
                timestamp
                amountUSD
                sender
                to
                amount0
                amount1
                transaction { id }
              }
              burns: burns(first: $first, orderBy: timestamp, orderDirection: desc, where: { pair: $pair }) {
                timestamp
                amountUSD
                sender
                to
                amount0
                amount1
                transaction { id }
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("pair", pair, "first", Math.min(200, MAX_TX)));
        } catch (Exception e) {
            return List.of();
        }

        List<PoolTransactionRowPayload> rows = new ArrayList<>();

        JsonNode swaps = data == null ? null : data.get("swaps");
        if (swaps != null && swaps.isArray()) {
            for (JsonNode n : swaps) {
                PoolTransactionRowPayload row = mapSwap(n);
                if (row != null) rows.add(row);
            }
        }

        JsonNode mints = data == null ? null : data.get("mints");
        if (mints != null && mints.isArray()) {
            for (JsonNode n : mints) {
                PoolTransactionRowPayload row = mapMintBurn(ExploreTxType.MINT, n);
                if (row != null) rows.add(row);
            }
        }

        JsonNode burns = data == null ? null : data.get("burns");
        if (burns != null && burns.isArray()) {
            for (JsonNode n : burns) {
                PoolTransactionRowPayload row = mapMintBurn(ExploreTxType.BURN, n);
                if (row != null) rows.add(row);
            }
        }

        rows.sort(Comparator.comparing(PoolTransactionRowPayload::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        if (rows.size() > MAX_TX) return rows.subList(0, MAX_TX);
        return rows;
    }

    private PoolTransactionRowPayload mapSwap(JsonNode n) {
        if (n == null) return null;
        JsonNode tx = n.get("transaction");
        String txHash = tx != null && tx.hasNonNull("id") ? tx.get("id").asText("") : "";
        if (txHash.isBlank()) return null;

        Long ts = null;
        Integer tsi = SubgraphJson.intValue(n, "timestamp");
        if (tsi != null) ts = (long) tsi;

        BigDecimal amountUsd = SubgraphJson.bigDecimal(n, "amountUSD");

        BigDecimal a0In = SubgraphJson.bigDecimal(n, "amount0In");
        BigDecimal a0Out = SubgraphJson.bigDecimal(n, "amount0Out");
        BigDecimal a1In = SubgraphJson.bigDecimal(n, "amount1In");
        BigDecimal a1Out = SubgraphJson.bigDecimal(n, "amount1Out");

        // Signed net flow: +in, -out
        BigDecimal token0Amount = a0In.subtract(a0Out);
        BigDecimal token1Amount = a1In.subtract(a1Out);

        String account =
                n.hasNonNull("from") ? n.get("from").asText("") :
                        n.hasNonNull("sender") ? n.get("sender").asText("") : "";

        return PoolTransactionRowPayload.builder()
                .type(ExploreTxType.SWAP)
                .timestamp(ts)
                .txHash(txHash)
                .amountUsd(amountUsd)
                .token0Amount(token0Amount)
                .token1Amount(token1Amount)
                .account(account)
                .build();
    }

    private PoolTransactionRowPayload mapMintBurn(ExploreTxType type, JsonNode n) {
        if (n == null || type == null) return null;
        JsonNode tx = n.get("transaction");
        String txHash = tx != null && tx.hasNonNull("id") ? tx.get("id").asText("") : "";
        if (txHash.isBlank()) return null;

        Long ts = null;
        Integer tsi = SubgraphJson.intValue(n, "timestamp");
        if (tsi != null) ts = (long) tsi;

        BigDecimal amountUsd = SubgraphJson.bigDecimal(n, "amountUSD");
        BigDecimal a0 = SubgraphJson.bigDecimal(n, "amount0");
        BigDecimal a1 = SubgraphJson.bigDecimal(n, "amount1");

        // Use signed convention for UI: MINT => + amounts, BURN => - amounts
        if (type == ExploreTxType.BURN) {
            a0 = a0.negate();
            a1 = a1.negate();
        }

        String account =
                n.hasNonNull("to") ? n.get("to").asText("") :
                        n.hasNonNull("sender") ? n.get("sender").asText("") : "";

        return PoolTransactionRowPayload.builder()
                .type(type)
                .timestamp(ts)
                .txHash(txHash)
                .amountUsd(amountUsd)
                .token0Amount(a0)
                .token1Amount(a1)
                .account(account)
                .build();
    }
}

