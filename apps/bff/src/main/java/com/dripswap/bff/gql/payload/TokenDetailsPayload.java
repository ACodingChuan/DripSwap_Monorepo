package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class TokenDetailsPayload {
    String chainId;
    String address;
    String symbol;
    String name;
    Integer decimals;
    BigDecimal priceUsd;
    BigDecimal change24hPct;
    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
    BigDecimal fdvUsd;
}

