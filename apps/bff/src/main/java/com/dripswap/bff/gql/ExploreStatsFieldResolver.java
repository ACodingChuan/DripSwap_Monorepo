package com.dripswap.bff.gql;

import com.dripswap.bff.gql.dataloader.ExploreStatsDataLoaderRegistrar;
import com.dripswap.bff.gql.model.ExploreStatsKey;
import com.dripswap.bff.gql.model.ExploreStatsSummary;
import com.dripswap.bff.gql.payload.ExploreSeriesPointPayload;
import com.dripswap.bff.gql.payload.ExploreStatsPayload;
import com.dripswap.bff.gql.payload.ExploreStatsSeed;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Field resolvers for ExploreStatsPayload (6.2).
 *
 * <p>We choose between summary vs full upstream fetch to avoid unnecessary series queries when the
 * client doesn't ask for them, while still sharing work via DataLoader.</p>
 */
@Controller
@RequiredArgsConstructor
public class ExploreStatsFieldResolver {

    private static final String CTX_NEEDS_SERIES_PREFIX = "exploreStats:needsSeries:";

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "chainId")
    public String chainId(ExploreStatsSeed src) {
        return src == null ? null : src.getChainId();
    }

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "tvlUsd")
    public CompletableFuture<BigDecimal> tvlUsd(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (src == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        return selectStats(src, env).thenApply(s -> s == null ? BigDecimal.ZERO : safe(s.getTvlUsd()));
    }

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "volume24hUsd")
    public CompletableFuture<BigDecimal> volume24hUsd(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (src == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        return selectStats(src, env).thenApply(s -> s == null ? BigDecimal.ZERO : safe(s.getVolume24hUsd()));
    }

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "fees24hUsd")
    public CompletableFuture<BigDecimal> fees24hUsd(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (src == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        return selectStats(src, env).thenApply(s -> s == null ? BigDecimal.ZERO : safe(s.getFees24hUsd()));
    }

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "tvlSeries")
    public CompletableFuture<List<ExploreSeriesPointPayload>> tvlSeries(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (src == null) {
            return CompletableFuture.completedFuture(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return loadFull(src, env).thenApply(s -> s == null ? ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO) : s.getTvlSeries());
    }

    @SchemaMapping(typeName = "ExploreStatsPayload", field = "volumeSeries")
    public CompletableFuture<List<ExploreSeriesPointPayload>> volumeSeries(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (src == null) {
            return CompletableFuture.completedFuture(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return loadFull(src, env).thenApply(s -> s == null ? ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO) : s.getVolumeSeries());
    }

    /**
     * Returns a synthetic ExploreStatsPayload for scalar fields:
     * - If the client also requests series, return full payload (one upstream request).
     * - Else use summary payload to avoid series query.
     */
    private CompletableFuture<ExploreStatsPayload> selectStats(ExploreStatsSeed src, DataFetchingEnvironment env) {
        if (needsSeries(src, env)) {
            return loadFull(src, env);
        }
        return loadSummary(src, env).thenApply(summary -> {
            BigDecimal tvl = summary == null ? BigDecimal.ZERO : safe(summary.getTvlUsd());
            BigDecimal vol = summary == null ? BigDecimal.ZERO : safe(summary.getVolume24hUsd());
            BigDecimal fees = vol.multiply(new BigDecimal("0.003"));
            return ExploreStatsPayload.builder()
                    .chainId(src.getChainId())
                    .tvlUsd(tvl)
                    .volume24hUsd(vol)
                    .fees24hUsd(fees)
                    // Series fields won't be asked for in this branch, but must be non-null if they are.
                    .tvlSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(tvl, tvl))
                    .volumeSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(vol, BigDecimal.ZERO))
                    .build();
        });
    }

    private CompletableFuture<ExploreStatsPayload> loadFull(ExploreStatsSeed src, DataFetchingEnvironment env) {
        DataLoader<ExploreStatsKey, ExploreStatsPayload> loader =
                env.getDataLoader(ExploreStatsDataLoaderRegistrar.DL_EXPLORE_STATS_FULL);
        if (loader == null) {
            return CompletableFuture.completedFuture(ExploreStatsPayload.builder()
                    .chainId(src.getChainId())
                    .tvlUsd(BigDecimal.ZERO)
                    .volume24hUsd(BigDecimal.ZERO)
                    .fees24hUsd(BigDecimal.ZERO)
                    .tvlSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO))
                    .volumeSeries(ExploreStatsSeriesUtil.synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO))
                    .build());
        }
        int days = src.getDays() == null ? 30 : Math.max(1, Math.min(src.getDays(), 90));
        return loader.load(new ExploreStatsKey(src.getChainId(), days)).toCompletableFuture();
    }

    private CompletableFuture<ExploreStatsSummary> loadSummary(ExploreStatsSeed src, DataFetchingEnvironment env) {
        DataLoader<String, ExploreStatsSummary> loader =
                env.getDataLoader(ExploreStatsDataLoaderRegistrar.DL_EXPLORE_STATS_SUMMARY);
        if (loader == null) {
            return CompletableFuture.completedFuture(new ExploreStatsSummary(BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return loader.load(src.getChainId()).toCompletableFuture();
    }

    private boolean needsSeries(ExploreStatsSeed src, DataFetchingEnvironment env) {
        String key = CTX_NEEDS_SERIES_PREFIX + (src == null ? "" : (src.getChainId() + ":" + src.getDays()));
        Object v = env.getGraphQlContext().get(key);
        return v instanceof Boolean b && b;
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
