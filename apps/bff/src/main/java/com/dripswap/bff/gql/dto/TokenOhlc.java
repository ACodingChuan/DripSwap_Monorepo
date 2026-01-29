package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenOhlc {
    Integer timestamp;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    BigDecimal volumeUsd;
    BigDecimal tvlUsd;
}
