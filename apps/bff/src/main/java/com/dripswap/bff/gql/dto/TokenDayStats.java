package com.dripswap.bff.gql.dto;

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
