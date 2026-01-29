package com.dripswap.bff.gql.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Recent transaction payload returned by GraphQL Query.recentTransactions (MVP-1 6.5).
 *
 * <p>decodedData is a JSON string; see specs/dripswap-mvp1-prd-v1.md#6.5 for the contract.</p>
 */
@Value
@Builder
public class TransactionPayload {
    String id;
    String chainId;
    Long blockNumber;
    String txHash;
    String decodedName;
    String decodedData;
    String status;
    String createdAt;
}
