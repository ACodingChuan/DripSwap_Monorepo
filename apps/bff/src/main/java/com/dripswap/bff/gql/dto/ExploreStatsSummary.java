package com.dripswap.bff.gql.dto;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class ExploreStatsSummary {
    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
}
