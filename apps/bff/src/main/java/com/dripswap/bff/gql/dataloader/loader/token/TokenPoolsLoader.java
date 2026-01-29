package com.dripswap.bff.gql.dataloader.loader.token;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenLitePayload;
import com.dripswap.bff.gql.dto.TokenPoolRow;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
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
 * Token pools list loader (MVP-1 6.8).
 *
 * <p>We cache a "top pools window" per token (up to {@link #MAX_POOLS}) and let the Query layer apply limit.</p>
 */
@Component
public class TokenPoolsLoader extends AbstractRedisSubgraphBatchLoader<TokenKey, Void, List<TokenPoolRow>> {

    private static final long TTL_SECONDS = 60;
    private static final int MAX_POOLS = 50;

    private final RedisCacheSupport redis;

    public TokenPoolsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
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
        return new CacheSpec(tokenId, RedisKeys.tokenPools(cid, tokenId));
    }

    @Override
    protected Map<String, List<TokenPoolRow>> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJsonList(ids, redisKeyFn, TokenPoolRow.class);
    }

    @Override
    protected void redisSet(String redisKey, List<TokenPoolRow> value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, List<TokenPoolRow>> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, List<TokenPoolRow>> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        // Token Details page requests a single token most of the time; do simple per-token queries for reliability.
        for (String tokenId : missIds) {
            if (tokenId == null || tokenId.isBlank()) continue;
            List<TokenPoolRow> rows = fetchPoolsForToken(endpoint, tokenId);
            out.put(tokenId.toLowerCase(Locale.ROOT), rows == null ? List.of() : rows);
        }
        return out;
    }

    private List<TokenPoolRow> fetchPoolsForToken(String endpoint, String tokenId) {
        int first = Math.min(250, MAX_POOLS * 5);

        String q = """
            query TokenPools($token: Bytes!, $first: Int!) {
              p0: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc, where: { token0: $token }) {
                id
                reserveUSD
                volumeUSD
                token0 { id symbol name }
                token1 { id symbol name }
              }
              p1: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc, where: { token1: $token }) {
                id
                reserveUSD
                volumeUSD
                token0 { id symbol name }
                token1 { id symbol name }
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("token", tokenId, "first", first));
        } catch (Exception e) {
            data = null;
        }

        if (data == null) {
            // Fallback: broad query + in-memory filter (may miss tokens that are not in top pairs).
            String fallback = """
                query TokenPoolsFallback($first: Int!) {
                  pairs: pairs(first: $first, orderBy: reserveUSD, orderDirection: desc) {
                    id
                    reserveUSD
                    volumeUSD
                    token0 { id symbol name }
                    token1 { id symbol name }
                  }
                }
                """;
            try {
                data = subgraphClient.query(endpoint, fallback, Map.of("first", 500));
            } catch (Exception e) {
                return List.of();
            }
        }

        Map<String, TokenPoolRow> byPair = new LinkedHashMap<>();
        boolean hasFilteredLists = data.has("p0") || data.has("p1");
        if (hasFilteredLists) {
            for (String key : List.of("p0", "p1")) {
                JsonNode pairs = data.get(key);
                if (pairs == null || !pairs.isArray()) continue;
                for (JsonNode n : pairs) {
                    TokenPoolRow row = mapPairRow(n);
                    if (row == null) continue;
                    byPair.putIfAbsent(row.getPairAddress(), row);
                }
            }
        } else {
            JsonNode pairs = data.get("pairs");
            if (pairs != null && pairs.isArray()) {
                for (JsonNode n : pairs) {
                    TokenPoolRow row = mapPairRow(n);
                    if (row == null) continue;
                    String t0 = row.getToken0() == null ? "" : row.getToken0().getAddress();
                    String t1 = row.getToken1() == null ? "" : row.getToken1().getAddress();
                    if (!tokenId.equalsIgnoreCase(t0) && !tokenId.equalsIgnoreCase(t1)) continue;
                    byPair.putIfAbsent(row.getPairAddress(), row);
                }
            }
        }

        List<TokenPoolRow> rows = new ArrayList<>(byPair.values());
        rows.sort(Comparator.comparing(TokenPoolRow::getTvlUsd, Comparator.nullsLast(Comparator.reverseOrder())));
        if (rows.size() > MAX_POOLS) rows = rows.subList(0, MAX_POOLS);
        return rows;
    }

    private TokenPoolRow mapPairRow(JsonNode n) {
        if (n == null) return null;
        String pair = n.hasNonNull("id") ? n.get("id").asText("") : "";
        if (pair.isBlank()) return null;

        BigDecimal tvlUsd = SubgraphJson.bigDecimal(n, "reserveUSD");
        BigDecimal volumeUsd = SubgraphJson.bigDecimal(n, "volumeUSD");

        JsonNode t0 = n.get("token0");
        JsonNode t1 = n.get("token1");

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

        return TokenPoolRow.builder()
                .pairAddress(pair)
                .tvlUsd(tvlUsd == null ? BigDecimal.ZERO : tvlUsd)
                .volumeUsd(volumeUsd == null ? BigDecimal.ZERO : volumeUsd)
                .token0(token0)
                .token1(token1)
                .build();
    }
}
