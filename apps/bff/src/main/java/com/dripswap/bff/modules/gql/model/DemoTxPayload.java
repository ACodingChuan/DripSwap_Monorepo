package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for demo transactions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DemoTxPayload {

    private Long id;
    private String txHash;
    private String chainId;
    private String status;
    private String createdAt;
}
