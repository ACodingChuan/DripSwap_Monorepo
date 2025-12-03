package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for token holdings in user portfolio.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenHoldingPayload {

    private String tokenAddress;
    private String tokenSymbol;
    private String balance;
    private String valueUSD;
}
