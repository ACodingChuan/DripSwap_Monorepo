package com.dripswap.bff.gql.dataloader.core;

import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphClient;
import lombok.RequiredArgsConstructor;
import org.dataloader.BatchLoaderEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Template-method base for "Redis read-through + Subgraph batch fetch" DataLoaders.
 *
 * <p>Flow per chain group:
 * group keys -> build cache specs -> redis mget -> compute misses -> subgraph fetch -> redis set -> merge.</p>
 */
@RequiredArgsConstructor
public abstract class AbstractRedisSubgraphBatchLoader<K, C, V> {

    protected final SubgraphClient subgraphClient;
    protected final SubgraphEndpointResolver endpointResolver;

    public final Mono<Map<K, V>> load(Set<K> keys, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> doLoad(keys)).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<K, V> doLoad(Set<K> keys) {
        Map<K, V> out = new HashMap<>();
        if (keys == null || keys.isEmpty()) return out;

        // Group by chainId for endpoint routing + batching.
        Map<String, List<K>> byChain = new LinkedHashMap<>();
        for (K key : keys) {
            if (key == null) continue;
            String chainId = chainIdOf(key);
            if (chainId == null || chainId.isBlank()) continue;
            byChain.computeIfAbsent(chainId, ignored -> new ArrayList<>()).add(key);
        }

        for (Map.Entry<String, List<K>> entry : byChain.entrySet()) {
            String chainId = entry.getKey();
            List<K> chainKeys = entry.getValue();
            if (chainKeys == null || chainKeys.isEmpty()) continue;

            String endpoint = endpointResolver.resolveV2(chainId);
            if (endpoint == null || endpoint.isBlank()) continue;

            C ctx = createContext(chainId, chainKeys);

            // Build cache specs and de-duplicate by spec.id.
            Map<String, CacheSpec> specById = new LinkedHashMap<>();
            Map<String, List<K>> keysById = new LinkedHashMap<>();
            for (K k : chainKeys) {
                CacheSpec spec = toCacheSpec(chainId, ctx, k);
                if (spec == null || spec.id() == null || spec.id().isBlank()) continue;
                if (spec.redisKey() == null || spec.redisKey().isBlank()) continue;
                String id = spec.id();
                specById.putIfAbsent(id, spec);
                keysById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(k);
            }

            if (specById.isEmpty()) continue;

            List<String> ids = new ArrayList<>(specById.keySet());
            Map<String, V> cachedByIdRaw = redisMget(ids, id -> specById.get(id).redisKey());
            Map<String, V> cachedById = cachedByIdRaw == null ? Map.of() : cachedByIdRaw;
            if (!cachedById.isEmpty()) {
                for (Map.Entry<String, V> e : cachedById.entrySet()) {
                    String id = e.getKey();
                    V v = e.getValue();
                    if (v == null) continue;
                    List<K> ks = keysById.get(id);
                    if (ks == null) continue;
                    for (K k : ks) out.put(k, v);
                }
            }

            // Determine misses by id (not by key).
            List<String> missIds = ids.stream().filter(id -> !cachedById.containsKey(id)).toList();
            if (missIds.isEmpty()) continue;

            // Fetch misses from subgraph (batch if possible).
            Map<String, V> fetchedById = fetchFromSubgraph(endpoint, chainId, ctx, missIds);
            if (fetchedById == null || fetchedById.isEmpty()) continue;

            for (Map.Entry<String, V> e : fetchedById.entrySet()) {
                String id = e.getKey();
                V v = e.getValue();
                if (id == null || id.isBlank() || v == null) continue;
                CacheSpec spec = specById.get(id);
                if (spec == null) continue;

                redisSet(spec.redisKey(), v, ttlSeconds());

                List<K> ks = keysById.get(id);
                if (ks == null) continue;
                for (K k : ks) out.put(k, v);
            }
        }

        return out;
    }

    protected abstract String chainIdOf(K key);

    /**
     * Optional per-chain context (e.g. compute todayStart/dayFrom once per request).
     */
    protected C createContext(String chainId, List<K> chainKeys) {
        return null;
    }

    /**
     * Defines how to cache a key in Redis (id + concrete redisKey).
     */
    protected abstract CacheSpec toCacheSpec(String chainId, C ctx, K key);

    /**
     * Cross-request cache read: return a map of id -> value.
     *
     * <p>Implementations should ignore missing values and return an empty map on errors.</p>
     */
    protected abstract Map<String, V> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn);

    /**
     * Cross-request cache write for fetched values.
     */
    protected abstract void redisSet(String redisKey, V value, long ttlSeconds);

    /**
     * Per-value TTL for Redis writes.
     */
    protected abstract long ttlSeconds();

    /**
     * Subgraph fetch for misses: return map of id -> value for ids that are found.
     */
    protected abstract Map<String, V> fetchFromSubgraph(String endpoint, String chainId, C ctx, List<String> missIds);
}
