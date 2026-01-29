package com.dripswap.bff.gql.dataloader.loader.pool;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.dto.PoolDetailsPayload;
import com.dripswap.bff.gql.dto.PoolKey;
import com.dripswap.bff.gql.dto.TokenLitePayload;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PoolDetailsLoader extends AbstractRedisSubgraphBatchLoader<PoolKey, Void, PoolDetailsPayload> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public PoolDetailsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
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
        return new CacheSpec(pair, RedisKeys.poolDetails(cid, pair));
    }

    @Override
    protected Map<String, PoolDetailsPayload> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, PoolDetailsPayload.class);
    }

    @Override
    protected void redisSet(String redisKey, PoolDetailsPayload value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, PoolDetailsPayload> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, PoolDetailsPayload> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        int first = Math.min(1000, missIds.size());
        String q = """
            query PoolDetails($pairIds: [Bytes!]!, $first: Int!) {
              pairs: pairs(first: $first, where: { id_in: $pairIds }) {
                id
                reserveUSD
                reserve0
                reserve1
                token0Price
                token1Price
                token0 { id symbol name }
                token1 { id symbol name }
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("pairIds", missIds, "first", first));
        } catch (Exception e) {
            return out;
        }

        JsonNode pairs = data == null ? null : data.get("pairs");
        if (pairs == null || !pairs.isArray()) return out;

        for (JsonNode n : pairs) {
            String pair = n.hasNonNull("id") ? n.get("id").asText("") : "";
            if (pair.isBlank()) continue;

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

            BigDecimal tvlUsd = SubgraphJson.bigDecimal(n, "reserveUSD");

            out.put(pair.toLowerCase(Locale.ROOT), PoolDetailsPayload.builder()
                    .chainId(chainId)
                    .pairAddress(pair)
                    .token0(token0)
                    .token1(token1)
                    .tvlUsd(tvlUsd == null ? BigDecimal.ZERO : tvlUsd)
                    // computed via PoolDetailsFieldResolver + PoolDayWindowStatsLoader
                    .volume24hUsd(null)
                    .fees24hUsd(null)
                    .tx24hCount(null)
                    .apr(null)
                    // from Pair entity
                    .reserve0(SubgraphJson.bigDecimal(n, "reserve0"))
                    .reserve1(SubgraphJson.bigDecimal(n, "reserve1"))
                    .token0Price(SubgraphJson.bigDecimal(n, "token0Price"))
                    .token1Price(SubgraphJson.bigDecimal(n, "token1Price"))
                    .build());
        }

        return out;
    }
}

