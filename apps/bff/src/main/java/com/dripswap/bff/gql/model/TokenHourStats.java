package com.dripswap.bff.gql.model;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class TokenHourStats {
    Integer periodStartUnix;
    BigDecimal open;
    BigDecimal close;
    BigDecimal volumeUsd;
}

