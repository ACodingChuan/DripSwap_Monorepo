package com.dripswap.bff.gql.resolver.field;

import com.dripswap.bff.gql.dataloader.UnifiedDataLoaderRegistrar;
import com.dripswap.bff.gql.dto.PoolDayWindowStats;
import com.dripswap.bff.gql.dto.PoolDetailsPayload;
import com.dripswap.bff.gql.dto.PoolKey;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;

@Controller
@RequiredArgsConstructor
public class PoolDetailsFieldResolver {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    @SchemaMapping(typeName = "PoolDetails", field = "volume24hUsd")
    public CompletableFuture<BigDecimal> volume24hUsd(PoolDetailsPayload pool, DataFetchingEnvironment env) {
        return loadStats(pool, env).thenApply(stats -> stats == null ? BigDecimal.ZERO : safe(stats.getVolume24hUsd()));
    }

    @SchemaMapping(typeName = "PoolDetails", field = "fees24hUsd")
    public CompletableFuture<BigDecimal> fees24hUsd(PoolDetailsPayload pool, DataFetchingEnvironment env) {
        return volume24hUsd(pool, env).thenApply(volume -> safe(volume).multiply(FEE_RATE));
    }

    @SchemaMapping(typeName = "PoolDetails", field = "tx24hCount")
    public CompletableFuture<Integer> tx24hCount(PoolDetailsPayload pool, DataFetchingEnvironment env) {
        return loadStats(pool, env).thenApply(stats -> stats == null ? null : stats.getTx24hCount());
    }

    @SchemaMapping(typeName = "PoolDetails", field = "apr")
    public CompletableFuture<BigDecimal> apr(PoolDetailsPayload pool, DataFetchingEnvironment env) {
        if (pool == null || pool.getTvlUsd() == null || pool.getTvlUsd().compareTo(BigDecimal.ZERO) <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        BigDecimal tvlUsd = pool.getTvlUsd();
        return fees24hUsd(pool, env).thenApply(fees -> {
            if (fees == null || fees.compareTo(BigDecimal.ZERO) <= 0) return null;
            return fees.multiply(new BigDecimal("365"))
                    .divide(tvlUsd, 8, RoundingMode.HALF_UP);
        });
    }

    private CompletableFuture<PoolDayWindowStats> loadStats(PoolDetailsPayload pool, DataFetchingEnvironment env) {
        if (pool == null || pool.getChainId() == null || pool.getPairAddress() == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<PoolKey, PoolDayWindowStats> loader =
                env.getDataLoader(UnifiedDataLoaderRegistrar.DL_POOL_DAY_WINDOW_STATS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }
        return loader.load(new PoolKey(pool.getChainId(), pool.getPairAddress()));
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

