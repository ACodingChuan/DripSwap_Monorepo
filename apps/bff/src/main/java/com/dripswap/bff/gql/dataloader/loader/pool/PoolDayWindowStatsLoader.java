package com.dripswap.bff.gql.dataloader.loader.pool;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.util.PoolDayWindowSupport;
import com.dripswap.bff.gql.dto.PoolDayWindowStats;
import com.dripswap.bff.gql.dto.PoolKey;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PoolDayWindowStatsLoader extends AbstractRedisSubgraphBatchLoader<PoolKey, PoolDayWindowSupport.DayWindow, PoolDayWindowStats> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public PoolDayWindowStatsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(PoolKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected PoolDayWindowSupport.DayWindow createContext(String chainId, List<PoolKey> chainKeys) {
        return PoolDayWindowSupport.DayWindow.now();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, PoolDayWindowSupport.DayWindow ctx, PoolKey key) {
        if (key == null || ctx == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        String pair = key.getPairAddress() == null ? "" : key.getPairAddress().trim().toLowerCase(Locale.ROOT);
        if (pair.isBlank()) return null;
        return new CacheSpec(pair, RedisKeys.poolDayWindow(cid, ctx.todayStart(), pair));
    }

    @Override
    protected Map<String, PoolDayWindowStats> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, PoolDayWindowStats.class);
    }

    @Override
    protected void redisSet(String redisKey, PoolDayWindowStats value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, PoolDayWindowStats> fetchFromSubgraph(
            String endpoint,
            String chainId,
            PoolDayWindowSupport.DayWindow window,
            List<String> missIds
    ) {
        Map<String, PoolDayWindowStats> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty() || window == null) return out;

        // Goldsky enforces `first` <= 1000.
        String q = """
            query PoolDayWindow($pairs: [Bytes!], $dayFrom: Int!, $first: Int!) {
              pairDayDatas(
                first: $first
                orderBy: date
                orderDirection: desc
                where: { pairAddress_in: $pairs, date_gte: $dayFrom }
              ) {
                pairAddress
                date
                reserveUSD
                dailyVolumeUSD
                dailyTxns
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("pairs", missIds, "dayFrom", window.dayFrom(), "first", 1000));
        } catch (Exception ignore) {
            return out;
        }

        Map<String, List<PoolDayWindowSupport.DayRow>> byPair = new HashMap<>();
        JsonNode rows = data == null ? null : data.get("pairDayDatas");
        if (rows != null && rows.isArray()) {
            for (JsonNode n : rows) {
                String pair = n.hasNonNull("pairAddress") ? n.get("pairAddress").asText("").toLowerCase(Locale.ROOT) : "";
                if (pair.isBlank()) continue;
                Integer date = SubgraphJson.intValue(n, "date");
                BigDecimal reserveUsd = SubgraphJson.bigDecimal(n, "reserveUSD");
                BigDecimal dailyVolumeUsd = SubgraphJson.bigDecimal(n, "dailyVolumeUSD");
                Integer dailyTxns = SubgraphJson.intValue(n, "dailyTxns");
                byPair.computeIfAbsent(pair, ignored -> new ArrayList<>())
                        .add(new PoolDayWindowSupport.DayRow(date, reserveUsd, dailyVolumeUsd, dailyTxns));
            }
        }

        for (String pair : missIds) {
            if (pair == null || pair.isBlank()) continue;
            List<PoolDayWindowSupport.DayRow> days = byPair.get(pair.toLowerCase(Locale.ROOT));
            out.put(pair.toLowerCase(Locale.ROOT), PoolDayWindowSupport.compute(window, days == null ? List.of() : days));
        }

        return out;
    }
}
