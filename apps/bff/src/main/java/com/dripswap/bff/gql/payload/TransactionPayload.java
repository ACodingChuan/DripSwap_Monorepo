package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

/**
 * GraphQL payload for recent transactions.
 *
 * <p>Matches {@code TransactionPayload} in {@code src/main/resources/graphql/schema.graphqls}.</p>
 */
@Value
@Builder
public class TransactionPayload {
    String id;
    String chainId;
    Long blockNumber;
    String txHash;
    String eventSig;
    String decodedName;
    String decodedData;
    String status;
    String createdAt;
}

