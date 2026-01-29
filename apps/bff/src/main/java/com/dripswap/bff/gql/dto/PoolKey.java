package com.dripswap.bff.gql.dto;

import lombok.Value;

/**
 * Composite key for pool-level DataLoaders (chainId + pairAddress).
 */
@Value
public class PoolKey {
    String chainId;
    String pairAddress;
}
