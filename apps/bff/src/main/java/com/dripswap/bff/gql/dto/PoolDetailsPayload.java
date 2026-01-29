package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class PoolDetailsPayload {
    String chainId;
    String pairAddress;
    TokenLitePayload token0;
    TokenLitePayload token1;

    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
    BigDecimal fees24hUsd;
    Integer tx24hCount;

    BigDecimal apr;
    BigDecimal reserve0;
    BigDecimal reserve1;
    BigDecimal token0Price;
    BigDecimal token1Price;
}

