package com.dripswap.bff.gql.dataloader.loader.token;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenLitePayload;
import com.dripswap.bff.gql.dto.TokenTransactionRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Token transactions loader (MVP-1 6.8).
 *
 * <p>Returns latest swaps for top pools of a token; caches a bounded window per token.</p>
 */
@Component
public class TokenTransactionsLoader extends AbstractRedisSubgraphBatchLoader<TokenKey, Void, List<TokenTransactionRow>> {

    private static final long TTL_SECONDS = 30;
    private static final int MAX_TX = 100;
    private static final int MAX_PAIRS = 25;

    private final RedisCacheSupport redis;

    public TokenTransactionsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(TokenKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, TokenKey key) {
        if (key == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        String tokenId = key.getTokenId() == null ? "" : key.getTokenId().trim().toLowerCase(Locale.ROOT);
        if (tokenId.isBlank()) return null;
        return new CacheSpec(tokenId, RedisKeys.tokenTransactions(cid, tokenId));
    }

    @Override
    protected Map<String, List<TokenTransactionRow>> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJsonList(ids, redisKeyFn, TokenTransactionRow.class);
    }

    @Override
    protected void redisSet(String redisKey, List<TokenTransactionRow> value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, List<TokenTransactionRow>> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, List<TokenTransactionRow>> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        for (String tokenId : missIds) {
            if (tokenId == null || tokenId.isBlank()) continue;
            List<TokenTransactionRow> rows = fetchTxForToken(endpoint, tokenId);
            out.put(tokenId.toLowerCase(Locale.ROOT), rows == null ? List.of() : rows);
        }
        return out;
    }

    private List<TokenTransactionRow> fetchTxForToken(String endpoint, String tokenId) {
        List<String> pairIds = fetchTopPairIds(endpoint, tokenId);
        if (pairIds.isEmpty()) return List.of();

        String q = """
            query TokenTransactions($pairs: [Bytes!]!, $first: Int!) {
              swaps: swaps(first: $first, orderBy: timestamp, orderDirection: desc, where: { pair_in: $pairs }) {
                id
                timestamp
                amountUSD
                amount0In
                amount1In
                amount0Out
                amount1Out
                pair { id token0 { id symbol name } token1 { id symbol name } }
                transaction { id }
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("pairs", pairIds, "first", Math.min(1000, MAX_TX)));
        } catch (Exception e) {
            return List.of();
        }

        JsonNode swaps = data == null ? null : data.get("swaps");
        if (swaps == null || !swaps.isArray() || swaps.isEmpty()) return List.of();

        List<TokenTransactionRow> rows = new ArrayList<>();
        for (JsonNode row : swaps) {
            TokenTransactionRow mapped = mapSwapRow(row);
            if (mapped != null) rows.add(mapped);
            if (rows.size() >= MAX_TX) break;
        }
        return rows;
    }

    private List<String> fetchTopPairIds(String endpoint, String tokenId) {
        int first = 200;
        String q = """
            query TokenPairs($token: Bytes!, $first: Int!) {
              p0: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc, where: { token0: $token }) { id reserveUSD }
              p1: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc, where: { token1: $token }) { id reserveUSD }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("token", tokenId, "first", first));
        } catch (Exception e) {
            data = null;
        }

        if (data == null) {
            String fallback = """
                query TokenPairsFallback($first: Int!) {
                  pairs: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc) {
                    id
                    reserveUSD
                    token0 { id }
                    token1 { id }
                  }
                }
                """;
            try {
                data = subgraphClient.query(endpoint, fallback, Map.of("first", 500));
            } catch (Exception e) {
                return List.of();
            }
        }

        Map<String, BigDecimal> tvlByPair = new LinkedHashMap<>();
        boolean hasFilteredLists = data.has("p0") || data.has("p1");
        if (hasFilteredLists) {
            for (String key : List.of("p0", "p1")) {
                JsonNode pairs = data.get(key);
                if (pairs == null || !pairs.isArray()) continue;
                for (JsonNode n : pairs) {
                    String id = n.hasNonNull("id") ? n.get("id").asText("") : "";
                    if (id.isBlank()) continue;
                    BigDecimal tvl = SubgraphJson.bigDecimal(n, "reserveUSD");
                    tvlByPair.putIfAbsent(id, tvl == null ? BigDecimal.ZERO : tvl);
                }
            }
        } else {
            JsonNode pairs = data.get("pairs");
            if (pairs != null && pairs.isArray()) {
                for (JsonNode n : pairs) {
                    String id = n.hasNonNull("id") ? n.get("id").asText("") : "";
                    if (id.isBlank()) continue;
                    JsonNode t0 = n.get("token0");
                    JsonNode t1 = n.get("token1");
                    String a0 = t0 != null && t0.hasNonNull("id") ? t0.get("id").asText("") : "";
                    String a1 = t1 != null && t1.hasNonNull("id") ? t1.get("id").asText("") : "";
                    if (!tokenId.equalsIgnoreCase(a0) && !tokenId.equalsIgnoreCase(a1)) continue;
                    BigDecimal tvl = SubgraphJson.bigDecimal(n, "reserveUSD");
                    tvlByPair.putIfAbsent(id, tvl == null ? BigDecimal.ZERO : tvl);
                }
            }
        }

        return tvlByPair.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PAIRS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private TokenTransactionRow mapSwapRow(JsonNode row) {
        if (row == null) return null;
        String id = row.hasNonNull("id") ? row.get("id").asText("") : "";
        if (id.isBlank()) return null;

        Integer timestamp = SubgraphJson.intValue(row, "timestamp");
        BigDecimal amountUsd = SubgraphJson.bigDecimal(row, "amountUSD");
        BigDecimal amount0In = SubgraphJson.bigDecimal(row, "amount0In");
        BigDecimal amount1In = SubgraphJson.bigDecimal(row, "amount1In");
        BigDecimal amount0Out = SubgraphJson.bigDecimal(row, "amount0Out");
        BigDecimal amount1Out = SubgraphJson.bigDecimal(row, "amount1Out");

        JsonNode pair = row.get("pair");
        String pairAddress = pair != null && pair.hasNonNull("id") ? pair.get("id").asText("") : "";

        JsonNode tx = row.get("transaction");
        String txHash = tx != null && tx.hasNonNull("id") ? tx.get("id").asText("") : "";

        JsonNode t0 = pair == null ? null : pair.get("token0");
        JsonNode t1 = pair == null ? null : pair.get("token1");
        TokenLitePayload token0 = TokenLitePayload.builder()
                .address(t0 != null && t0.hasNonNull("id") ? t0.get("id").asText("") : "")
                .symbol(t0 != null && t0.hasNonNull("symbol") ? t0.get("symbol").asText("") : "")
                .name(t0 != null && t0.hasNonNull("name") ? t0.get("name").asText("") : "")
                .build();
        TokenLitePayload token1 = TokenLitePayload.builder()
                .address(t1 != null && t1.hasNonNull("id") ? t1.get("id").asText("") : "")
                .symbol(t1 != null && t1.hasNonNull("symbol") ? t1.get("symbol").asText("") : "")
                .name(t1 != null && t1.hasNonNull("name") ? t1.get("name").asText("") : "")
                .build();

        return TokenTransactionRow.builder()
                .id(id)
                .timestamp(timestamp == null ? 0 : timestamp)
                .txHash(txHash)
                .pairAddress(pairAddress)
                .amountUsd(amountUsd == null ? BigDecimal.ZERO : amountUsd)
                .token0(token0)
                .token1(token1)
                .amount0In(amount0In == null ? BigDecimal.ZERO : amount0In)
                .amount1In(amount1In == null ? BigDecimal.ZERO : amount1In)
                .amount0Out(amount0Out == null ? BigDecimal.ZERO : amount0Out)
                .amount1Out(amount1Out == null ? BigDecimal.ZERO : amount1Out)
                .build();
    }
}
