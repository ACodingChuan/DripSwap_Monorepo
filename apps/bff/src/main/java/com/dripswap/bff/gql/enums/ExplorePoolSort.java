package com.dripswap.bff.gql.enums;

/**
 * Sort options for Explore pools query (MVP-1 6.4).
 *
 * <p>Must stay in sync with GraphQL schema enum ExplorePoolSort.</p>
 */
public enum ExplorePoolSort {
    TVL_DESC,
    VOLUME_24H_DESC,
    TX_24H_DESC,
    FEES_24H_DESC,
    APR_DESC
}
