package com.dripswap.bff.gql.dto;

import lombok.Value;

import java.math.BigDecimal;

/**
 * Latest token TVL snapshot from TokenHourData.
 */
@Value
public class TokenTvlSnapshot {
    Integer periodStartUnix;
    BigDecimal tvlUsd;
}
