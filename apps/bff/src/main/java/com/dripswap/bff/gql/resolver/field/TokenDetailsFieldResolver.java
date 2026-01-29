package com.dripswap.bff.gql.resolver.field;

import com.dripswap.bff.gql.dataloader.UnifiedDataLoaderRegistrar;
import com.dripswap.bff.gql.dto.ExploreTokenRowPayload;
import com.dripswap.bff.gql.dto.TokenDetailsPayload;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenTvlSnapshot;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * GraphQL field resolver for TokenDetails computed fields (MVP-1 6.6).
 */
@Controller
@RequiredArgsConstructor
public class TokenDetailsFieldResolver {

    private final ExploreTokenRowFieldResolver exploreTokenRowFieldResolver;

    @SchemaMapping(typeName = "TokenDetails", field = "priceUsd")
    public CompletableFuture<BigDecimal> priceUsd(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token == null || token.getDerivedETH() == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        return exploreTokenRowFieldResolver.priceUsd(asExploreRow(token), env)
                .thenApply(v -> v == null ? BigDecimal.ZERO : v);
    }

    @SchemaMapping(typeName = "TokenDetails", field = "change24hPct")
    public CompletableFuture<BigDecimal> change24hPct(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        // Reuse Explore Tokens "1d change" logic; Token Details labels it as "24h change".
        return exploreTokenRowFieldResolver.change1d(asExploreRow(token), env)
                .thenApply(v -> v == null ? BigDecimal.ZERO : v);
    }

    @SchemaMapping(typeName = "TokenDetails", field = "volume24hUsd")
    public CompletableFuture<BigDecimal> volume24hUsd(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        return exploreTokenRowFieldResolver.volume24hUsd(asExploreRow(token), env)
                .thenApply(v -> v == null ? BigDecimal.ZERO : safe(v));
    }

    @SchemaMapping(typeName = "TokenDetails", field = "tvlUsd")
    public CompletableFuture<BigDecimal> tvlUsd(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);

        DataLoader<TokenKey, TokenTvlSnapshot> tvlLoader = env.getDataLoader(UnifiedDataLoaderRegistrar.DL_TOKEN_LATEST_TVL);
        if (tvlLoader == null) {
            return fallbackTvlFromLiquidity(token, env);
        }
        return tvlLoader.load(new TokenKey(token.getChainId(), token.getAddress()))
                .thenCompose(snapshot -> {
                    if (snapshot != null && snapshot.getTvlUsd() != null && snapshot.getTvlUsd().compareTo(BigDecimal.ZERO) > 0) {
                        return CompletableFuture.completedFuture(snapshot.getTvlUsd());
                    }
                    return fallbackTvlFromLiquidity(token, env);
                });
    }

    private CompletableFuture<BigDecimal> fallbackTvlFromLiquidity(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token.getTotalLiquidity() == null || token.getTotalLiquidity().compareTo(BigDecimal.ZERO) <= 0) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        return priceUsd(token, env).thenApply(price -> token.getTotalLiquidity().multiply(safe(price)));
    }

    @SchemaMapping(typeName = "TokenDetails", field = "fdvUsd")
    public CompletableFuture<BigDecimal> fdvUsd(TokenDetailsPayload token, DataFetchingEnvironment env) {
        if (token == null || token.getTotalSupply() == null || token.getDecimals() == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (token.getTotalSupply().compareTo(BigDecimal.ZERO) <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        return exploreTokenRowFieldResolver.fdvUsd(asExploreRow(token), env);
    }

    private ExploreTokenRowPayload asExploreRow(TokenDetailsPayload token) {
        // Build an ExploreTokenRow-shaped source so we can reuse its SchemaMapping logic verbatim.
        return ExploreTokenRowPayload.builder()
                .id(token.getAddress())
                .chainId(token.getChainId())
                .symbol(token.getSymbol())
                .name(token.getName())
                .decimals(token.getDecimals())
                .totalSupply(token.getTotalSupply())
                .derivedETH(token.getDerivedETH())
                .build();
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
