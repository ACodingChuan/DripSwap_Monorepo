package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Token details seed/payload (MVP-1 6.6).
 *
 * <p>QueryResolver fills base token fields. Expensive/computed fields are resolved via
 * {@code @SchemaMapping(typeName="TokenDetails", field="...")}.</p>
 */
@Value
@Builder
public class TokenDetailsPayload {
    String chainId;
    String address;
    String symbol;
    String name;
    Integer decimals;

    // Base fields used for derived calculations
    BigDecimal totalSupply;
    BigDecimal derivedETH;
    BigDecimal totalLiquidity;

    // Computed fields (resolved by field resolver)
    BigDecimal priceUsd;
    BigDecimal change24hPct;
    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
    BigDecimal fdvUsd;
}
