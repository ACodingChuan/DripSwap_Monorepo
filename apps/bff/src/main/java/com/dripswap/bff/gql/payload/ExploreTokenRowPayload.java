package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ExploreTokenRowPayload {
    String id;
    String chainId;
    String symbol;
    String name;
    Integer decimals;
    BigDecimal totalSupply;
    BigDecimal derivedETH;
    
    // Computed fields (calculated in FieldResolver)
    BigDecimal priceUsd;
    BigDecimal change1h;
    BigDecimal change1d;
    BigDecimal fdvUsd;
    BigDecimal volume24hUsd;
}

