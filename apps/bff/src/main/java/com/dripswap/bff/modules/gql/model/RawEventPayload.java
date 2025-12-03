package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for raw blockchain events.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RawEventPayload {

    private Long id;
    private String chainId;
    private Long blockNumber;
    private String txHash;
    private Integer logIndex;
    private String eventSig;
    private String rawData;
    private String createdAt;
}
