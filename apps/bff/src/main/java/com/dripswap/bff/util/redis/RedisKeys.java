package com.dripswap.bff.util.redis;

import java.util.Locale;

/**
 * Redis key builders used by GraphQL DataLoaders.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String exploreStatsSummary(String chainId) {
        return String.format(Locale.ROOT, "ds:v2:%s:explore:stats:summary", chainId);
    }

    public static String exploreStatsFull(String chainId, int days) {
        return String.format(Locale.ROOT, "ds:v2:%s:explore:stats:%d", chainId, days);
    }

    public static String bundleEthPrice(String chainId) {
        return String.format(Locale.ROOT, "ds:v2:%s:bundle:ethPrice", chainId);
    }

    public static String tokenDayStats(String chainId, String tokenId) {
        return String.format(Locale.ROOT, "ds:v2:%s:token:%s:dayStats", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    public static String tokenHourStats(String chainId, String tokenId) {
        return String.format(Locale.ROOT, "ds:v2:%s:token:%s:hourStats", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    public static String poolDayWindow(String chainId, int todayStart, String pair) {
        return String.format(Locale.ROOT, "ds:v2:%s:pool:%s:dayWindow:%d", chainId, pair.toLowerCase(Locale.ROOT), todayStart);
    }

    public static String tokenTvl(String chainId, String tokenId) {
        return String.format(Locale.ROOT, "ds:v2:%s:token:%s:tvl", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    public static String tokenPools(String chainId, String tokenId) {
        return String.format(Locale.ROOT, "ds:v2:%s:token:%s:pools", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    public static String tokenTransactions(String chainId, String tokenId) {
        return String.format(Locale.ROOT, "ds:v2:%s:token:%s:tx", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    public static String poolDetails(String chainId, String pairAddress) {
        return String.format(Locale.ROOT, "ds:v2:%s:pool:%s:details", chainId, pairAddress.toLowerCase(Locale.ROOT));
    }

    public static String poolCandles(String chainId, String pairAddress, String interval, int toBucket) {
        return String.format(
                Locale.ROOT,
                "ds:v2:%s:pool:%s:candles:%s:%d",
                chainId,
                pairAddress.toLowerCase(Locale.ROOT),
                interval.toUpperCase(Locale.ROOT),
                toBucket
        );
    }

    public static String poolTransactions(String chainId, String pairAddress) {
        return String.format(Locale.ROOT, "ds:v2:%s:pool:%s:tx", chainId, pairAddress.toLowerCase(Locale.ROOT));
    }
}
