package com.dripswap.bff.gql.dto;

import lombok.Value;

/**
 * Composite key for exploreStats DataLoader.
 */
@Value
public class ExploreStatsKey {
    String chainId;
    Integer days;
}
