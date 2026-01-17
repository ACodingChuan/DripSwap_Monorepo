package com.dripswap.bff.gql.payload;

import lombok.Value;

/**
 * Lightweight source object for {@code ExploreStatsPayload} GraphQL type.
 *
 * <p>We keep {@code Query.exploreStats} lean and compute fields via {@code @SchemaMapping}
 * + DataLoader (with Redis read-through caching).</p>
 */
@Value
public class ExploreStatsSeed {
    String chainId;
    Integer days;
}

