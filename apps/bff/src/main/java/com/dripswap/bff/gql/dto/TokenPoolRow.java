package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenPoolRow {
    String pairAddress;
    BigDecimal tvlUsd;
    BigDecimal volumeUsd;
    TokenLitePayload token0;
    TokenLitePayload token1;
}

