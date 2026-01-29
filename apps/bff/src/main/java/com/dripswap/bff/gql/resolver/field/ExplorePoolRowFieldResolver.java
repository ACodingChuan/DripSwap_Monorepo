package com.dripswap.bff.gql.resolver.field;

import com.dripswap.bff.gql.dataloader.UnifiedDataLoaderRegistrar;
import com.dripswap.bff.gql.dto.ExplorePoolRowPayload;
import com.dripswap.bff.gql.dto.PoolDayWindowStats;
import com.dripswap.bff.gql.dto.PoolKey;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;

/**
 * GraphQL field resolver for ExplorePoolRow computed fields.
 *
 * <p>Uses DataLoader (+ Redis read-through) to batch PairDayData window fetching.</p>
 */
@Controller
@RequiredArgsConstructor
public class ExplorePoolRowFieldResolver {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    @SchemaMapping(typeName = "ExplorePoolRow", field = "volume24hUsd")
    public CompletableFuture<BigDecimal> volume24hUsd(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        return loadStats(poolRow, env).thenApply(stats -> stats == null ? BigDecimal.ZERO : safe(stats.getVolume24hUsd()));
    }

    @SchemaMapping(typeName = "ExplorePoolRow", field = "fees24hUsd")
    public CompletableFuture<BigDecimal> fees24hUsd(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        return volume24hUsd(poolRow, env).thenApply(volume -> safe(volume).multiply(FEE_RATE));
    }

    @SchemaMapping(typeName = "ExplorePoolRow", field = "tx24hCount")
    public CompletableFuture<Integer> tx24hCount(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        return loadStats(poolRow, env).thenApply(stats -> stats == null ? null : stats.getTx24hCount());
    }

    @SchemaMapping(typeName = "ExplorePoolRow", field = "tvlChange1d")
    public CompletableFuture<BigDecimal> tvlChange1d(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        return loadStats(poolRow, env).thenApply(stats -> stats == null ? null : stats.getTvlChange1d());
    }

    @SchemaMapping(typeName = "ExplorePoolRow", field = "volume1wUsd")
    public CompletableFuture<BigDecimal> volume1wUsd(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        return loadStats(poolRow, env).thenApply(stats -> stats == null ? null : stats.getVolume1wUsd());
    }

    @SchemaMapping(typeName = "ExplorePoolRow", field = "apr")
    public CompletableFuture<BigDecimal> apr(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        if (poolRow == null || poolRow.getTvlUsd() == null || poolRow.getTvlUsd().compareTo(BigDecimal.ZERO) <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        BigDecimal tvlUsd = poolRow.getTvlUsd();
        return fees24hUsd(poolRow, env).thenApply(fees -> {
            if (fees == null || fees.compareTo(BigDecimal.ZERO) <= 0) return null;
            // Simplified APR ratio (e.g. 0.12 means 12%).
            return fees.multiply(new BigDecimal("365"))
                    .divide(tvlUsd, 8, RoundingMode.HALF_UP);
        });
    }

    private CompletableFuture<PoolDayWindowStats> loadStats(ExplorePoolRowPayload poolRow, DataFetchingEnvironment env) {
        if (poolRow == null || poolRow.getChainId() == null || poolRow.getPairAddress() == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<PoolKey, PoolDayWindowStats> loader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_DAY_WINDOW_STATS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }
        return loader.load(new PoolKey(poolRow.getChainId(), poolRow.getPairAddress()));
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
