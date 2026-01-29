package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class PoolOhlc {
    Integer timestamp;
    BigDecimal tvlUsd;
    BigDecimal volumeUsd;
    BigDecimal feesUsd;
}

