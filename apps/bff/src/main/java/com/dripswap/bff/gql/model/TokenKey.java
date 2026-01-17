package com.dripswap.bff.gql.model;

import lombok.Value;

/**
 * Composite key for token-scoped derived data.
 *
 * <p>We include {@code chainId} because the same token address may exist on multiple chains.</p>
 */
@Value
public class TokenKey {
    String chainId;
    String tokenId;
}

