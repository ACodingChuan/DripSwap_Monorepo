package com.dripswap.bff.gql.dataloader.core;

/**
 * Cache specification for a single batch key.
 *
 * @param id stable identifier used for de-duplication within a chain group (e.g. tokenId)
 * @param redisKey concrete Redis key for cross-request caching
 */
public record CacheSpec(String id, String redisKey) {
}
