package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for parsed transactions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionPayload {

    private Long id;
    private String chainId;
    private Long blockNumber;
    private String txHash;
    private String eventSig;
    private String decodedName;
    private String decodedData;
    private String status;
    private String createdAt;
}
