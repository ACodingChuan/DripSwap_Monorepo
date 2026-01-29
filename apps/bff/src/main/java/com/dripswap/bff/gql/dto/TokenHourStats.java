package com.dripswap.bff.gql.dto;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class TokenHourStats {
    Integer periodStartUnix;
    BigDecimal open;
    BigDecimal close;
    BigDecimal volumeUsd;
}
