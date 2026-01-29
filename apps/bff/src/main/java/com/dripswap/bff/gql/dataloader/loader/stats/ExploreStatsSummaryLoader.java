package com.dripswap.bff.gql.dataloader.loader.stats;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.gql.dto.ExploreStatsSummary;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExploreStatsSummaryLoader extends AbstractRedisSubgraphBatchLoader<String, Void, ExploreStatsSummary> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public ExploreStatsSummaryLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(String key) {
        return key;
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, String key) {
        String id = key == null ? "" : key.trim();
        if (id.isBlank()) return null;
        return new CacheSpec(id, RedisKeys.exploreStatsSummary(id));
    }

    @Override
    protected Map<String, ExploreStatsSummary> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, ExploreStatsSummary.class);
    }

    @Override
    protected void redisSet(String redisKey, ExploreStatsSummary value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, ExploreStatsSummary> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, ExploreStatsSummary> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        String q = """
            query ExploreStatsSummary {
              factories: uniswapFactories(first: 1) { totalLiquidityUSD }
              latestDay: uniswapDayDatas(first: 1, orderBy: date, orderDirection: desc) { dailyVolumeUSD }
            }
            """;

        for (String id : missIds) {
            if (id == null || id.isBlank()) continue;
            try {
                JsonNode data = subgraphClient.query(endpoint, q, Map.of());

                BigDecimal tvlUsd = BigDecimal.ZERO;
                JsonNode factories = data == null ? null : data.get("factories");
                if (factories != null && factories.isArray() && !factories.isEmpty()) {
                    tvlUsd = SubgraphJson.bigDecimal(factories.get(0), "totalLiquidityUSD");
                }

                BigDecimal volume24hUsd = BigDecimal.ZERO;
                JsonNode latestDay = data == null ? null : data.get("latestDay");
                if (latestDay != null && latestDay.isArray() && !latestDay.isEmpty()) {
                    volume24hUsd = SubgraphJson.bigDecimal(latestDay.get(0), "dailyVolumeUSD");
                }

                out.put(id, new ExploreStatsSummary(tvlUsd, volume24hUsd));
            } catch (Exception e) {
                // Preserve previous behavior: always provide a fallback object on failures.
                out.put(id, new ExploreStatsSummary(BigDecimal.ZERO, BigDecimal.ZERO));
            }
        }

        return out;
    }
}
