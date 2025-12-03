package com.dripswap.bff.modules.gql.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL payload for subgraph status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubgraphStatusPayload {

    private String chainId;
    private Boolean isHealthy;
    private Long syncedBlock;
    private Long headBlock;
    private Integer indexingErrorCount;
    private String message;
}
