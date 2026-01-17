package com.dripswap.bff.gql.model;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class ExploreStatsSummary {
    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
}

