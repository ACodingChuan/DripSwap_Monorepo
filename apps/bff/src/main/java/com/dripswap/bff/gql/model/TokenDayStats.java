package com.dripswap.bff.gql.model;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class TokenDayStats {
    Integer latestDate;
    BigDecimal latestPriceUsd;
    BigDecimal prevPriceUsd;
    BigDecimal latestVolumeUsd;
    BigDecimal latestTxns;
}

