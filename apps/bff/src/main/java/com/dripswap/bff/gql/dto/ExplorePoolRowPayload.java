package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ExplorePoolRowPayload {
    String pairAddress;
    String chainId;
    TokenLitePayload token0;
    TokenLitePayload token1;

    BigDecimal tvlUsd;
    BigDecimal tvlChange1d;

    BigDecimal volume24hUsd;
    BigDecimal volume1wUsd;

    BigDecimal fees24hUsd;
    Integer tx24hCount;

    BigDecimal apr;
}
