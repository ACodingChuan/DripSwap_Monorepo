package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenOhlcPayload {
    Integer timestamp;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    BigDecimal volumeUsd;
    BigDecimal tvlUsd;
}

