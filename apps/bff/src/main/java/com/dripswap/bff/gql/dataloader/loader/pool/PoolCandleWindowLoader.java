package com.dripswap.bff.gql.dataloader.loader.pool;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.dto.PoolCandleKey;
import com.dripswap.bff.gql.dto.PoolOhlc;
import com.dripswap.bff.gql.enums.PoolChartInterval;
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

/**
 * Pool chart window loader (6.10).
 *
 * <p>Returns a bucketed series (hour/day) for a bounded window ending at {@code toBucket}.</p>
 */
@Component
public class PoolCandleWindowLoader extends AbstractRedisSubgraphBatchLoader<PoolCandleKey, Void, List<PoolOhlc>> {

    private static final long TTL_SECONDS = 60;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    // Minute/hour retention in Goldsky can be limited; for pools we assume:
    private static final int WINDOW_HOURS = 32 * 24;
    private static final int WINDOW_DAYS = 400;

    private final RedisCacheSupport redis;

    public PoolCandleWindowLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(PoolCandleKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, PoolCandleKey key) {
        if (key == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        String pair = key.getPairAddress() == null ? "" : key.getPairAddress().trim().toLowerCase(Locale.ROOT);
        if (pair.isBlank()) return null;
        PoolChartInterval interval = key.getInterval();
        if (interval == null) return null;
        Integer toBucket = key.getToBucket();
        if (toBucket == null || toBucket <= 0) return null;

        String id = interval.name() + ":" + pair + ":" + toBucket;
        String redisKey = RedisKeys.poolCandles(cid, pair, interval.name(), toBucket);
        return new CacheSpec(id, redisKey);
    }

    @Override
    protected Map<String, List<PoolOhlc>> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJsonList(ids, redisKeyFn, PoolOhlc.class);
    }

    @Override
    protected void redisSet(String redisKey, List<PoolOhlc> value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, List<PoolOhlc>> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, List<PoolOhlc>> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        // Fetch per series to avoid subgraph filter differences across entities.
        for (String id : missIds) {
            Parsed parsed = Parsed.tryParse(id);
            if (parsed == null) continue;
            List<PoolOhlc> rows = fetchSeries(endpoint, parsed.interval, parsed.pair, parsed.toBucket);
            out.put(id, rows == null ? List.of() : rows);
        }
        return out;
    }

    private List<PoolOhlc> fetchSeries(String endpoint, PoolChartInterval interval, String pair, int toBucket) {
        if (interval == PoolChartInterval.HOUR) {
            int from = Math.max(0, toBucket - (WINDOW_HOURS * 3600));
            from = from - (from % 3600);
            String q = """
                query PoolHourCandles($pair: Bytes!, $from: Int!, $to: Int!, $first: Int!) {
                  rows: pairHourDatas(
                    first: $first
                    orderBy: hourStartUnix
                    orderDirection: asc
                    where: { pair: $pair, hourStartUnix_gte: $from, hourStartUnix_lte: $to }
                  ) {
                    hourStartUnix
                    reserveUSD
                    hourlyVolumeUSD
                  }
                }
                """;
            JsonNode data;
            try {
                data = subgraphClient.query(endpoint, q, Map.of("pair", pair, "from", from, "to", toBucket, "first", 1000));
            } catch (Exception e) {
                return List.of();
            }
            JsonNode rows = data == null ? null : data.get("rows");
            if (rows == null || !rows.isArray()) return List.of();

            List<PoolOhlc> out = new ArrayList<>();
            for (JsonNode n : rows) {
                Integer ts = SubgraphJson.intValue(n, "hourStartUnix");
                if (ts == null) continue;
                BigDecimal tvlUsd = SubgraphJson.bigDecimal(n, "reserveUSD");
                BigDecimal vol = SubgraphJson.bigDecimal(n, "hourlyVolumeUSD");
                out.add(PoolOhlc.builder()
                        .timestamp(ts)
                        .tvlUsd(tvlUsd)
                        .volumeUsd(vol)
                        .feesUsd(vol.multiply(FEE_RATE))
                        .build());
            }
            return out;
        }

        int from = Math.max(0, toBucket - (WINDOW_DAYS * 86_400));
        from = from - (from % 86_400);
        String q = """
            query PoolDayCandles($pair: Bytes!, $from: Int!, $to: Int!, $first: Int!) {
              rows: pairDayDatas(
                first: $first
                orderBy: date
                orderDirection: asc
                where: { pairAddress: $pair, date_gte: $from, date_lte: $to }
              ) {
                date
                reserveUSD
                dailyVolumeUSD
              }
            }
            """;
        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("pair", pair, "from", from, "to", toBucket, "first", 1000));
        } catch (Exception e) {
            return List.of();
        }
        JsonNode rows = data == null ? null : data.get("rows");
        if (rows == null || !rows.isArray()) return List.of();

        List<PoolOhlc> out = new ArrayList<>();
        for (JsonNode n : rows) {
            Integer ts = SubgraphJson.intValue(n, "date");
            if (ts == null) continue;
            BigDecimal tvlUsd = SubgraphJson.bigDecimal(n, "reserveUSD");
            BigDecimal vol = SubgraphJson.bigDecimal(n, "dailyVolumeUSD");
            out.add(PoolOhlc.builder()
                    .timestamp(ts)
                    .tvlUsd(tvlUsd)
                    .volumeUsd(vol)
                    .feesUsd(vol.multiply(FEE_RATE))
                    .build());
        }
        return out;
    }

    private record Parsed(PoolChartInterval interval, String pair, int toBucket) {
        static Parsed tryParse(String id) {
            if (id == null || id.isBlank()) return null;
            String[] parts = id.split(":", 3);
            if (parts.length != 3) return null;
            PoolChartInterval interval;
            try {
                interval = PoolChartInterval.valueOf(parts[0]);
            } catch (Exception e) {
                return null;
            }
            String pair = parts[1] == null ? "" : parts[1].trim().toLowerCase(Locale.ROOT);
            if (pair.isBlank()) return null;
            int toBucket;
            try {
                toBucket = Integer.parseInt(parts[2]);
            } catch (Exception e) {
                return null;
            }
            if (toBucket <= 0) return null;
            return new Parsed(interval, pair, toBucket);
        }
    }
}

