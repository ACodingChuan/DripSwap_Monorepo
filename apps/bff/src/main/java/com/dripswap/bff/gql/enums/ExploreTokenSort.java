package com.dripswap.bff.gql.enums;

/**
 * Sort options for Explore tokens list.
 *
 * <p>Note: MVP-1 keeps this argument optional for forward compatibility. The resolver
 * may ignore it until the frontend exposes sorting UI.</p>
 */
public enum ExploreTokenSort {
    VOLUME_24H_DESC,
    TVL_DESC,
    PRICE_DESC,
    FDV_DESC,
    CHANGE_1H_DESC,
    CHANGE_1D_DESC
}
