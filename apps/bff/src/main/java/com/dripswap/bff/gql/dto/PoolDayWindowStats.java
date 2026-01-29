package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Pre-aggregated day-window stats for Explore Pools / Pool Details.
 *
 * <p>Values are computed server-side from subgraph PairDayData rows.</p>
 */
@Value
@Builder
public class PoolDayWindowStats {
    Integer latestDate; // PairDayData.date (unix day start in seconds)
    BigDecimal volume24hUsd;
    Integer tx24hCount;
    BigDecimal tvlChange1d; // percentage, e.g. 12.34 means +12.34%
    BigDecimal volume1wUsd;
}
