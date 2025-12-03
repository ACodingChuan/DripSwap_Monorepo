package com.dripswap.bff.modules.gql.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for user portfolio.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPortfolioPayload {

    private String address;
    private String totalValueUSD;
    private List<TokenHoldingPayload> holdings;
    private Integer transactionCount;
}
