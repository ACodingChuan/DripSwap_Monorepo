package com.dripswap.bff.gql.resolver.field;

import com.dripswap.bff.gql.dataloader.UnifiedDataLoaderRegistrar;
import com.dripswap.bff.gql.dto.ExploreTokenRowPayload;
import com.dripswap.bff.gql.dto.TokenDayStats;
import com.dripswap.bff.gql.dto.TokenHourStats;
import com.dripswap.bff.gql.dto.TokenKey;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * GraphQL field resolver for ExploreTokenRow computed fields.
 *
 * <p>We use DataLoader to batch external subgraph requests (Goldsky) and keep the
 * main QueryResolver.exploreTokens lean.</p>
 */
@Controller
@RequiredArgsConstructor
public class ExploreTokenRowFieldResolver {

    @SchemaMapping(typeName = "ExploreTokenRow", field = "priceUsd")
    public CompletableFuture<BigDecimal> priceUsd(ExploreTokenRowPayload tokenRow, DataFetchingEnvironment env) {
        if (tokenRow == null || tokenRow.getDerivedETH() == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<String, BigDecimal> ethPriceLoader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_ETH_PRICE);
        if (ethPriceLoader == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ethPriceLoader.load(tokenRow.getChainId())
                .thenApply(ethPrice -> {
                    if (ethPrice == null) return null;
                    BigDecimal priceUsd = tokenRow.getDerivedETH().multiply(ethPrice);
                    return priceUsd.compareTo(BigDecimal.ZERO) > 0 ? priceUsd : null;
                });
    }

    @SchemaMapping(typeName = "ExploreTokenRow", field = "fdvUsd")
    public CompletableFuture<BigDecimal> fdvUsd(ExploreTokenRowPayload tokenRow, DataFetchingEnvironment env) {
        if (tokenRow == null || tokenRow.getTotalSupply() == null || tokenRow.getDecimals() == null) {
            return CompletableFuture.completedFuture(null);
        }

        return priceUsd(tokenRow, env).thenApply(priceUsd -> {
            if (priceUsd == null || priceUsd.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            try {
                BigDecimal divisor = BigDecimal.TEN.pow(Math.max(0, tokenRow.getDecimals()));
                BigDecimal scaledSupply = tokenRow.getTotalSupply().divide(divisor, 8, RoundingMode.HALF_UP);
                return scaledSupply.multiply(priceUsd);
            } catch (Exception e) {
                return null;
            }
        });
    }

    @SchemaMapping(typeName = "ExploreTokenRow", field = "change1h")
    public CompletableFuture<BigDecimal> change1h(ExploreTokenRowPayload tokenRow, DataFetchingEnvironment env) {
        if (tokenRow == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<TokenKey, TokenHourStats> loader =
                env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_HOUR_STATS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }

        TokenKey key = new TokenKey(tokenRow.getChainId(), tokenRow.getId());
        return loader.load(key).thenApply(stats -> {
            if (stats == null) return null;

            long nowSec = Instant.now().getEpochSecond();
            int currentHourStart = (int) (nowSec - (nowSec % 3600));

            if (stats.getPeriodStartUnix() == null || stats.getPeriodStartUnix() < (currentHourStart - 3600)) {
                return null;
            }
            if (stats.getVolumeUsd() == null || stats.getVolumeUsd().compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            if (stats.getOpen() == null || stats.getClose() == null) {
                return null;
            }
            if (stats.getOpen().compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            return stats.getClose().subtract(stats.getOpen())
                    .divide(stats.getOpen(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        });
    }

    @SchemaMapping(typeName = "ExploreTokenRow", field = "change1d")
    public CompletableFuture<BigDecimal> change1d(ExploreTokenRowPayload tokenRow, DataFetchingEnvironment env) {
        if (tokenRow == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<TokenKey, TokenDayStats> loader =
                env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_DAY_STATS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }

        TokenKey key = new TokenKey(tokenRow.getChainId(), tokenRow.getId());
        return loader.load(key).thenApply(stats -> {
            if (stats == null) return null;

            // For Explore Tokens, "change" should reflect swap-driven price movement.
            // tokenDayDatas.dailyTxns can include non-swap activity; we gate on volumeUSD only.
            if (stats.getLatestVolumeUsd() == null || stats.getLatestVolumeUsd().compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }

            long nowSec = Instant.now().getEpochSecond();
            int todayStart = (int) (nowSec - (nowSec % 86_400));
            if (stats.getLatestDate() == null || stats.getLatestDate() < (todayStart - 86_400)) {
                return null;
            }

            if (stats.getPrevPriceUsd() == null || stats.getLatestPriceUsd() == null) {
                return null;
            }
            if (stats.getPrevPriceUsd().compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            return stats.getLatestPriceUsd().subtract(stats.getPrevPriceUsd())
                    .divide(stats.getPrevPriceUsd(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        });
    }

    @SchemaMapping(typeName = "ExploreTokenRow", field = "volume24hUsd")
    public CompletableFuture<BigDecimal> volume24hUsd(ExploreTokenRowPayload tokenRow, DataFetchingEnvironment env) {
        if (tokenRow == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<TokenKey, TokenDayStats> loader =
                env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_DAY_STATS);
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }

        TokenKey key = new TokenKey(tokenRow.getChainId(), tokenRow.getId());
        return loader.load(key).thenApply(stats -> {
            if (stats == null) return null;

            // Keep "24h" semantics strict: if we don't have a recent day bucket, treat it as missing.
            long nowSec = Instant.now().getEpochSecond();
            int todayStart = (int) (nowSec - (nowSec % 86_400));
            if (stats.getLatestDate() == null || stats.getLatestDate() < (todayStart - 86_400)) {
                return null;
            }

            BigDecimal v = stats.getLatestVolumeUsd();
            if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) return null;
            return v;
        });
    }
}
