package com.dripswap.bff.gql.dataloader;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.gql.ExploreStatsSeriesUtil;
import com.dripswap.bff.gql.payload.ExploreSeriesPointPayload;
import com.dripswap.bff.gql.payload.ExploreStatsPayload;
import com.dripswap.bff.gql.model.ExploreStatsKey;
import com.dripswap.bff.gql.model.ExploreStatsSummary;
import com.dripswap.bff.sync.SubgraphClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dataloader.BatchLoaderEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DataLoaders for Explore Stats (6.2).
 *
 * <p>Goal:
 * - Field resolvers can share one upstream fetch per request (DataLoader)
 * - Redis read-through cache to reduce Goldsky requests.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreStatsDataLoaderRegistrar {

    public static final String DL_EXPLORE_STATS_SUMMARY = "exploreStatsSummary";
    public static final String DL_EXPLORE_STATS_FULL = "exploreStatsFull";

    private static final long TTL_SECONDS = 60;

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final SubgraphProperties subgraphProperties;
    private final SubgraphClient subgraphClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    public void register() {
        batchLoaderRegistry.<String, ExploreStatsSummary>forName(DL_EXPLORE_STATS_SUMMARY)
                .withName(DL_EXPLORE_STATS_SUMMARY)
                .registerMappedBatchLoader(this::loadSummary);

        batchLoaderRegistry.<ExploreStatsKey, ExploreStatsPayload>forName(DL_EXPLORE_STATS_FULL)
                .withName(DL_EXPLORE_STATS_FULL)
                .registerMappedBatchLoader(this::loadFull);
    }

    private Mono<Map<String, ExploreStatsSummary>> loadSummary(Set<String> chainIds, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> {
            Map<String, ExploreStatsSummary> out = new HashMap<>();
            if (chainIds == null || chainIds.isEmpty()) return out;

            List<String> ids = chainIds.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList();
            if (ids.isEmpty()) return out;

            // 1) Redis MGET
            List<String> redisKeys = ids.stream().map(this::redisSummaryKey).toList();
            List<String> cached = redisTemplate.opsForValue().multiGet(redisKeys);
            if (cached != null && !cached.isEmpty()) {
                for (int i = 0; i < ids.size(); i++) {
                    String raw = cached.size() > i ? cached.get(i) : null;
                    if (raw == null || raw.isBlank()) continue;
                    try {
                        out.put(ids.get(i), objectMapper.readValue(raw, ExploreStatsSummary.class));
                    } catch (Exception ignore) {
                    }
                }
            }

            // 2) Miss -> query Goldsky
            for (String chainId : ids) {
                if (out.containsKey(chainId)) continue;
                String endpoint = resolveSubgraphEndpoint(chainId);
                if (endpoint == null || endpoint.isBlank()) continue;

                try {
                    String q = """
                        query ExploreStatsSummary {
                          factories: uniswapFactories(first: 1) { totalLiquidityUSD }
                          latestDay: uniswapDayDatas(first: 1, orderBy: date, orderDirection: desc) { dailyVolumeUSD }
                        }
                        """;
                    JsonNode data = subgraphClient.query(endpoint, q, Map.of());

                    BigDecimal tvlUsd = BigDecimal.ZERO;
                    JsonNode factories = data == null ? null : data.get("factories");
                    if (factories != null && factories.isArray() && !factories.isEmpty()) {
                        tvlUsd = parseBigDecimalField(factories.get(0), "totalLiquidityUSD");
                    }

                    BigDecimal volume24hUsd = BigDecimal.ZERO;
                    JsonNode latestDay = data == null ? null : data.get("latestDay");
                    if (latestDay != null && latestDay.isArray() && !latestDay.isEmpty()) {
                        volume24hUsd = parseBigDecimalField(latestDay.get(0), "dailyVolumeUSD");
                    }

                    ExploreStatsSummary summary = new ExploreStatsSummary(tvlUsd, volume24hUsd);
                    out.put(chainId, summary);

                    try {
                        redisTemplate.opsForValue().set(
                                redisSummaryKey(chainId),
                                objectMapper.writeValueAsString(summary),
                                java.time.Duration.ofSeconds(TTL_SECONDS)
                        );
                    } catch (Exception ignore) {
                    }
                } catch (Exception e) {
                    log.warn("ExploreStatsSummary loader failed: chainId={}, err={}", chainId, e.getMessage());
                }
            }

            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<ExploreStatsKey, ExploreStatsPayload>> loadFull(Set<ExploreStatsKey> keys, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> {
            Map<ExploreStatsKey, ExploreStatsPayload> out = new HashMap<>();
            if (keys == null || keys.isEmpty()) return out;

            Map<String, List<ExploreStatsKey>> byChain = new HashMap<>();
            for (ExploreStatsKey key : keys) {
                if (key == null || key.getChainId() == null || key.getDays() == null) continue;
                byChain.computeIfAbsent(key.getChainId(), k -> new ArrayList<>()).add(key);
            }

            for (Map.Entry<String, List<ExploreStatsKey>> entry : byChain.entrySet()) {
                String chainId = entry.getKey();
                List<ExploreStatsKey> chainKeys = entry.getValue();
                String endpoint = resolveSubgraphEndpoint(chainId);
                if (endpoint == null || endpoint.isBlank()) continue;

                // Redis MGET: we cache per (chainId, days)
                List<String> redisKeys = chainKeys.stream()
                        .map(k -> redisFullKey(chainId, k.getDays()))
                        .toList();
                List<String> cached = redisTemplate.opsForValue().multiGet(redisKeys);
                if (cached != null && !cached.isEmpty()) {
                    for (int i = 0; i < chainKeys.size(); i++) {
                        String raw = cached.size() > i ? cached.get(i) : null;
                        if (raw == null || raw.isBlank()) continue;
                        try {
                            ExploreStatsPayload payload = objectMapper.readValue(raw, ExploreStatsPayload.class);
                            out.put(chainKeys.get(i), payload);
                        } catch (Exception ignore) {
                        }
                    }
                }

                for (ExploreStatsKey key : chainKeys) {
                    if (key == null) continue;
                    if (out.containsKey(key)) continue;

                    int days = Math.max(1, Math.min(key.getDays(), 90));
                    try {
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
                        JsonNode data = subgraphClient.query(endpoint, q, Map.of("days", days));

                        BigDecimal tvlUsd = BigDecimal.ZERO;
                        JsonNode factories = data == null ? null : data.get("factories");
                        if (factories != null && factories.isArray() && !factories.isEmpty()) {
                            tvlUsd = parseBigDecimalField(factories.get(0), "totalLiquidityUSD");
                        }

                        BigDecimal volume24hUsd = BigDecimal.ZERO;
                        JsonNode latestDay = data == null ? null : data.get("latestDay");
                        if (latestDay != null && latestDay.isArray() && !latestDay.isEmpty()) {
                            volume24hUsd = parseBigDecimalField(latestDay.get(0), "dailyVolumeUSD");
                        }

                        JsonNode series = data == null ? null : data.get("series");
                        List<ExploreSeriesPointPayload> tvlSeries = new ArrayList<>();
                        List<ExploreSeriesPointPayload> volumeSeries = new ArrayList<>();
                        if (series != null && series.isArray()) {
                            for (JsonNode row : series) {
                                Integer date = row.hasNonNull("date") ? row.get("date").asInt() : null;
                                if (date == null) continue;
                                tvlSeries.add(ExploreSeriesPointPayload.builder()
                                        .date(date)
                                        .valueUsd(parseBigDecimalField(row, "totalLiquidityUSD"))
                                        .build());
                                volumeSeries.add(ExploreSeriesPointPayload.builder()
                                        .date(date)
                                        .valueUsd(parseBigDecimalField(row, "dailyVolumeUSD"))
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
                        out.put(key, payload);

                        try {
                            redisTemplate.opsForValue().set(
                                    redisFullKey(chainId, days),
                                    objectMapper.writeValueAsString(payload),
                                    java.time.Duration.ofSeconds(TTL_SECONDS)
                            );
                        } catch (Exception ignore) {
                        }

                        // Also refresh summary cache opportunistically.
                        try {
                            ExploreStatsSummary summary = new ExploreStatsSummary(tvlUsd, volume24hUsd);
                            redisTemplate.opsForValue().set(
                                    redisSummaryKey(chainId),
                                    objectMapper.writeValueAsString(summary),
                                    java.time.Duration.ofSeconds(TTL_SECONDS)
                            );
                        } catch (Exception ignore) {
                        }
                    } catch (Exception e) {
                        log.warn("ExploreStatsFull loader failed: chainId={}, days={}, err={}", chainId, days, e.getMessage());
                        out.put(
                                key,
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
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String resolveSubgraphEndpoint(String chainId) {
        if (subgraphProperties == null || subgraphProperties.getChains() == null) return null;
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (chain == null) continue;
            if (!Objects.equals(chain.getId(), chainId)) continue;
            if (!chain.isEnabled()) continue;
            return chain.getEndpointV2();
        }
        return null;
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

    private String redisSummaryKey(String chainId) {
        return String.format(Locale.ROOT, "ds:v2:%s:explore:stats:summary", chainId);
    }

    // Keep the existing key format used by the original exploreStats implementation.
    private String redisFullKey(String chainId, int days) {
        return String.format(Locale.ROOT, "ds:v2:%s:explore:stats:%d", chainId, days);
    }
}
