package com.dripswap.bff.gql.dataloader.loader.token;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
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
public class EthPriceLoader extends AbstractRedisSubgraphBatchLoader<String, Void, BigDecimal> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public EthPriceLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
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
        return new CacheSpec(id, RedisKeys.bundleEthPrice(id));
    }

    @Override
    protected Map<String, BigDecimal> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetBigDecimal(ids, redisKeyFn);
    }

    @Override
    protected void redisSet(String redisKey, BigDecimal value, long ttlSeconds) {
        redis.setBigDecimal(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, BigDecimal> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        String q = """
            query LatestEthPrice {
              latestBundle: bundles(first: 1, orderBy: timestamp, orderDirection: desc) {
                ethPrice
              }
            }
            """;

        for (String id : missIds) {
            if (id == null || id.isBlank()) continue;
            try {
                JsonNode data = subgraphClient.query(endpoint, q, Map.of());
                BigDecimal ethPrice = BigDecimal.ZERO;
                JsonNode bundles = data == null ? null : data.get("latestBundle");
                if (bundles != null && bundles.isArray() && !bundles.isEmpty()) {
                    ethPrice = SubgraphJson.bigDecimal(bundles.get(0), "ethPrice");
                }
                out.put(id, ethPrice);
            } catch (Exception ignore) {
                // Keep behavior aligned with the previous implementation: on errors we return no value.
            }
        }

        return out;
    }
}
