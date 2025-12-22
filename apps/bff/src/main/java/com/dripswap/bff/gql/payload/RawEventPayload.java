package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

/**
 * GraphQL payload for raw blockchain events.
 *
 * <p>Matches {@code RawEventPayload} in {@code src/main/resources/graphql/schema.graphqls}.</p>
 */
@Value
@Builder
public class RawEventPayload {
    String id;
    String chainId;
    Long blockNumber;
    String txHash;
    Integer logIndex;
    String eventSig;
    String rawData;
    String createdAt;
}

