package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for trading pairs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PairPayload {

    private String id;
    private String token0;
    private String token1;
    private String reserve0;
    private String reserve1;
    private String totalSupply;
    private String volumeUSD;
    private String feesUSD;
}
