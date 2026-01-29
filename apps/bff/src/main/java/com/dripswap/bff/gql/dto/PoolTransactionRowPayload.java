package com.dripswap.bff.gql.dto;

import com.dripswap.bff.gql.enums.ExploreTxType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class PoolTransactionRowPayload {
    ExploreTxType type;
    Long timestamp;
    String txHash;
    BigDecimal amountUsd;
    BigDecimal token0Amount;
    BigDecimal token1Amount;
    String account;
}

