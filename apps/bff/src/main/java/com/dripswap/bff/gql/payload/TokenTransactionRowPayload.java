package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenTransactionRowPayload {
    String id;
    Long timestamp;
    String txHash;
    String pairAddress;
    BigDecimal amountUsd;
    TokenLitePayload token0;
    TokenLitePayload token1;
    BigDecimal amount0In;
    BigDecimal amount1In;
    BigDecimal amount0Out;
    BigDecimal amount1Out;
}

