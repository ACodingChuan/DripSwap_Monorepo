package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for tokens.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPayload {

    private String id;
    private String name;
    private String symbol;
    private Integer decimals;
    private String totalSupply;
    private String derivedETH;
    private String volumeUSD;
    private String feesUSD;
}
