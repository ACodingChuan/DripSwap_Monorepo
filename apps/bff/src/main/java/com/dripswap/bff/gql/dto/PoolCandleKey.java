package com.dripswap.bff.gql.dto;

import com.dripswap.bff.gql.enums.PoolChartInterval;
import lombok.Value;

/**
 * Key for pool candle window loaders.
 *
 * <p>We include a coarse-grained {@code toBucket} so cached windows remain correct for different requests.</p>
 */
@Value
public class PoolCandleKey {
    String chainId;
    String pairAddress;
    PoolChartInterval interval;
    Integer toBucket;
}

