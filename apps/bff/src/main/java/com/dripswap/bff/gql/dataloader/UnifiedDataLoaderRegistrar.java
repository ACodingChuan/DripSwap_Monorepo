package com.dripswap.bff.gql.dataloader;

import com.dripswap.bff.gql.dataloader.loader.pool.PoolDayWindowStatsLoader;
import com.dripswap.bff.gql.dataloader.loader.pool.PoolDetailsLoader;
import com.dripswap.bff.gql.dataloader.loader.pool.PoolCandleWindowLoader;
import com.dripswap.bff.gql.dataloader.loader.pool.PoolTransactionsLoader;
import com.dripswap.bff.gql.dataloader.loader.stats.ExploreStatsFullLoader;
import com.dripswap.bff.gql.dataloader.loader.stats.ExploreStatsSummaryLoader;
import com.dripswap.bff.gql.dataloader.loader.token.EthPriceLoader;
import com.dripswap.bff.gql.dataloader.loader.token.TokenDayStatsLoader;
import com.dripswap.bff.gql.dataloader.loader.token.TokenHourStatsLoader;
import com.dripswap.bff.gql.dataloader.loader.token.TokenLatestTvlLoader;
import com.dripswap.bff.gql.dataloader.loader.token.TokenPoolsLoader;
import com.dripswap.bff.gql.dataloader.loader.token.TokenTransactionsLoader;
import com.dripswap.bff.gql.dto.ExploreStatsKey;
import com.dripswap.bff.gql.dto.ExploreStatsPayload;
import com.dripswap.bff.gql.dto.ExploreStatsSummary;
import com.dripswap.bff.gql.dto.PoolDayWindowStats;
import com.dripswap.bff.gql.dto.PoolCandleKey;
import com.dripswap.bff.gql.dto.PoolKey;
import com.dripswap.bff.gql.dto.PoolOhlc;
import com.dripswap.bff.gql.dto.TokenDayStats;
import com.dripswap.bff.gql.dto.TokenHourStats;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenTvlSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;

/**
 * Centralized DataLoader registration for GraphQL.
 *
 * <p>This consolidates multiple *DataLoaderRegistrar classes into a single place so:
 * - loader names are easy to find
 * - cross-feature reuse of loaders is encouraged
 * - registration is deterministic (one Spring component registers all loaders)</p>
 */
@Component
@RequiredArgsConstructor
public class UnifiedDataLoaderRegistrar {

    // --- Loader names (must stay stable) ---
    public static final String DL_EXPLORE_STATS_SUMMARY = "exploreStatsSummary";
    public static final String DL_EXPLORE_STATS_FULL = "exploreStatsFull";

    public static final String DL_ETH_PRICE = "ethPrice";
    public static final String DL_TOKEN_DAY_STATS = "tokenDayStats";
    public static final String DL_TOKEN_HOUR_STATS = "tokenHourStats";

    public static final String DL_POOL_DAY_WINDOW_STATS = "poolDayWindowStats";
    public static final String DL_POOL_DETAILS = "poolDetails";
    public static final String DL_POOL_CANDLES = "poolCandles";
    public static final String DL_POOL_TRANSACTIONS = "poolTransactions";

    public static final String DL_TOKEN_LATEST_TVL = "tokenLatestTvl";
    public static final String DL_TOKEN_POOLS = "tokenPools";
    public static final String DL_TOKEN_TRANSACTIONS = "tokenTransactions";

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final ExploreStatsSummaryLoader exploreStatsSummaryLoader;
    private final ExploreStatsFullLoader exploreStatsFullLoader;
    private final EthPriceLoader ethPriceLoader;
    private final TokenDayStatsLoader tokenDayStatsLoader;
    private final TokenHourStatsLoader tokenHourStatsLoader;
    private final PoolDayWindowStatsLoader poolDayWindowStatsLoader;
    private final PoolDetailsLoader poolDetailsLoader;
    private final PoolCandleWindowLoader poolCandleWindowLoader;
    private final PoolTransactionsLoader poolTransactionsLoader;
    private final TokenLatestTvlLoader tokenLatestTvlLoader;
    private final TokenPoolsLoader tokenPoolsLoader;
    private final TokenTransactionsLoader tokenTransactionsLoader;

    @jakarta.annotation.PostConstruct
    public void register() {
        // Explore stats (6.2)
        batchLoaderRegistry.<String, ExploreStatsSummary>forName(DL_EXPLORE_STATS_SUMMARY)
                .withName(DL_EXPLORE_STATS_SUMMARY)
                .registerMappedBatchLoader(exploreStatsSummaryLoader::load);

        batchLoaderRegistry.<ExploreStatsKey, ExploreStatsPayload>forName(DL_EXPLORE_STATS_FULL)
                .withName(DL_EXPLORE_STATS_FULL)
                .registerMappedBatchLoader(exploreStatsFullLoader::load);

        // Explore tokens (6.3)
        batchLoaderRegistry.<String, java.math.BigDecimal>forName(DL_ETH_PRICE)
                .withName(DL_ETH_PRICE)
                .registerMappedBatchLoader(ethPriceLoader::load);

        batchLoaderRegistry.<TokenKey, TokenDayStats>forName(DL_TOKEN_DAY_STATS)
                .withName(DL_TOKEN_DAY_STATS)
                .registerMappedBatchLoader(tokenDayStatsLoader::load);

        batchLoaderRegistry.<TokenKey, TokenHourStats>forName(DL_TOKEN_HOUR_STATS)
                .withName(DL_TOKEN_HOUR_STATS)
                .registerMappedBatchLoader(tokenHourStatsLoader::load);

        // Explore pools (6.4)
        batchLoaderRegistry.<PoolKey, PoolDayWindowStats>forName(DL_POOL_DAY_WINDOW_STATS)
                .withName(DL_POOL_DAY_WINDOW_STATS)
                .registerMappedBatchLoader(poolDayWindowStatsLoader::load);

        // Pool details (6.9)
        batchLoaderRegistry.<PoolKey, com.dripswap.bff.gql.dto.PoolDetailsPayload>forName(DL_POOL_DETAILS)
                .withName(DL_POOL_DETAILS)
                .registerMappedBatchLoader(poolDetailsLoader::load);

        // Pool candles / transactions (6.10)
        batchLoaderRegistry.<PoolCandleKey, java.util.List<PoolOhlc>>forName(DL_POOL_CANDLES)
                .withName(DL_POOL_CANDLES)
                .registerMappedBatchLoader(poolCandleWindowLoader::load);

        batchLoaderRegistry.<PoolKey, java.util.List<com.dripswap.bff.gql.dto.PoolTransactionRowPayload>>forName(DL_POOL_TRANSACTIONS)
                .withName(DL_POOL_TRANSACTIONS)
                .registerMappedBatchLoader(poolTransactionsLoader::load);

        // Token details (6.6)
        batchLoaderRegistry.<TokenKey, TokenTvlSnapshot>forName(DL_TOKEN_LATEST_TVL)
                .withName(DL_TOKEN_LATEST_TVL)
                .registerMappedBatchLoader(tokenLatestTvlLoader::load);

        // Token pools / transactions (6.8)
        batchLoaderRegistry.<TokenKey, java.util.List<com.dripswap.bff.gql.dto.TokenPoolRow>>forName(DL_TOKEN_POOLS)
                .withName(DL_TOKEN_POOLS)
                .registerMappedBatchLoader(tokenPoolsLoader::load);

        batchLoaderRegistry.<TokenKey, java.util.List<com.dripswap.bff.gql.dto.TokenTransactionRow>>forName(DL_TOKEN_TRANSACTIONS)
                .withName(DL_TOKEN_TRANSACTIONS)
                .registerMappedBatchLoader(tokenTransactionsLoader::load);
    }
}
