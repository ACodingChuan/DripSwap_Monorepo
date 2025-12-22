package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ExploreSeriesPointPayload {
    Integer date;
    BigDecimal valueUsd;
}

