package com.dripswap.bff.gql.model;

import lombok.Value;

/**
 * Composite key for exploreStats DataLoader.
 */
@Value
public class ExploreStatsKey {
    String chainId;
    Integer days;
}

