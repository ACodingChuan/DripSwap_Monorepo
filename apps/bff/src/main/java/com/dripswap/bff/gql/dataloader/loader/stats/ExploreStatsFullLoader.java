package com.dripswap.bff.gql.dataloader.loader.stats;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.gql.util.ExploreStatsSeriesUtil;
import com.dripswap.bff.gql.dto.ExploreSeriesPointPayload;
import com.dripswap.bff.gql.dto.ExploreStatsKey;
import com.dripswap.bff.gql.dto.ExploreStatsPayload;
import com.dripswap.bff.gql.dto.ExploreStatsSummary;
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
import java.util.Map;

@Component
public class ExploreStatsFullLoader extends AbstractRedisSubgraphBatchLoader<ExploreStatsKey, Void, ExploreStatsPayload> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public ExploreStatsFullLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(ExploreStatsKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, ExploreStatsKey key) {
        if (key == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        int days = key.getDays() == null ? 1 : key.getDays();
        days = Math.max(1, Math.min(90, days));
        String id = Integer.toString(days);
        return new CacheSpec(id, RedisKeys.exploreStatsFull(cid, days));
    }

    @Override
    protected Map<String, ExploreStatsPayload> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, ExploreStatsPayload.class);
    }

    @Override
    protected void redisSet(String redisKey, ExploreStatsPayload value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, ExploreStatsPayload> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, ExploreStatsPayload> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        String q = """
            query ExploreStatsFull($days: Int!) {
              factories: uniswapFactories(first: 1) { totalLiquidityUSD }
              latestDay: uniswapDayDatas(first: 1, orderBy: date, orderDirection: desc) { dailyVolumeUSD }
              series: uniswapDayDatas(first: $days, orderBy: date, orderDirection: desc) {
                date
                totalLiquidityUSD
                dailyVolumeUSD
              }
            }
            """;

        for (String id : missIds) {
            int days;
            try {
                days = Integer.parseInt(id == null ? "" : id.trim());
            } catch (Exception ignore) {
                days = 1;
            }
            days = Math.max(1, Math.min(90, days));
            String normId = Integer.toString(days);

            try {
                JsonNode data = subgraphClient.query(endpoint, q, Map.of("days", days));

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

                List<ExploreSeriesPointPayload> tvlSeries = new ArrayList<>();
                List<ExploreSeriesPointPayload> volumeSeries = new ArrayList<>();

                JsonNode series = data == null ? null : data.get("series");
                if (series != null && series.isArray()) {
                    for (JsonNode row : series) {
                        Integer date = row.hasNonNull("date") ? row.get("date").asInt() : null;
                        if (date == null) continue;
                        tvlSeries.add(ExploreSeriesPointPayload.builder()
                                .date(date)
                                .valueUsd(SubgraphJson.bigDecimal(row, "totalLiquidityUSD"))
                                .build());
                        volumeSeries.add(ExploreSeriesPointPayload.builder()
                                .date(date)
                                .valueUsd(SubgraphJson.bigDecimal(row, "dailyVolumeUSD"))
                                .build());
                    }
                }

                tvlSeries.sort(Comparator.comparingInt(ExploreSeriesPointPayload::getDate));
                volumeSeries.sort(Comparator.comparingInt(ExploreSeriesPointPayload::getDate));

                if (tvlSeries.isEmpty() && tvlUsd.compareTo(BigDecimal.ZERO) > 0) {
                    tvlSeries = ExploreStatsSeriesUtil.synthesizeDailySeries(tvlUsd, tvlUsd);
                }
                if (volumeSeries.isEmpty() && volume24hUsd.compareTo(BigDecimal.ZERO) > 0) {
                    volumeSeries = ExploreStatsSeriesUtil.synthesizeDailySeries(volume24hUsd, BigDecimal.ZERO);
                }

                tvlSeries = ExploreStatsSeriesUtil.padSeriesIfNeeded(tvlSeries, tvlUsd);
                volumeSeries = ExploreStatsSeriesUtil.padSeriesIfNeeded(volumeSeries, BigDecimal.ZERO);

                if (volume24hUsd.compareTo(BigDecimal.ZERO) == 0 && !volumeSeries.isEmpty()) {
                    volume24hUsd = ExploreStatsSeriesUtil.safeBigDecimal(volumeSeries.get(volumeSeries.size() - 1).getValueUsd());
                }
                if (tvlUsd.compareTo(BigDecimal.ZERO) == 0 && !tvlSeries.isEmpty()) {
                    tvlUsd = ExploreStatsSeriesUtil.safeBigDecimal(tvlSeries.get(tvlSeries.size() - 1).getValueUsd());
                }

                BigDecimal fees24hUsd = volume24hUsd.multiply(new BigDecimal("0.003"));

                ExploreStatsPayload payload = ExploreStatsPayload.builder()
                        .chainId(chainId)
                        .tvlUsd(tvlUsd)
                        .volume24hUsd(volume24hUsd)
                        .fees24hUsd(fees24hUsd)
                        .tvlSeries(tvlSeries)
                        .volumeSeries(volumeSeries)
                        .build();

                out.put(normId, payload);

                // Keep the summary cache warm when full stats are fetched.
                redis.setJson(RedisKeys.exploreStatsSummary(chainId), new ExploreStatsSummary(tvlUsd, volume24hUsd), TTL_SECONDS);
            } catch (Exception e) {
                out.put(
                        normId,
                        ExploreStatsPayload.builder()
                                .chainId(chainId)
                                .tvlUsd(BigDecimal.ZERO)
                                .volume24hUsd(BigDecimal.ZERO)
                                .fees24hUsd(BigDecimal.ZERO)
                                .tvlSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO))
                                .volumeSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO))
                                .build()
                );
            }
        }

        return out;
    }
}
