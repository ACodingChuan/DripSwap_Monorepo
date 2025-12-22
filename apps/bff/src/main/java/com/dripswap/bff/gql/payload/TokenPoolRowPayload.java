package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenPoolRowPayload {
    String pairAddress;
    TokenLitePayload token0;
    TokenLitePayload token1;
    BigDecimal tvlUsd;
    BigDecimal volumeUsd;
}

