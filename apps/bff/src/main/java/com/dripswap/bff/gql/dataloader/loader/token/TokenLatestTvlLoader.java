package com.dripswap.bff.gql.dataloader.loader.token;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.gql.dto.TokenTvlSnapshot;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TokenLatestTvlLoader extends AbstractRedisSubgraphBatchLoader<TokenKey, Void, TokenTvlSnapshot> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public TokenLatestTvlLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
        super(subgraphClient, endpointResolver);
        this.redis = redis;
    }

    @Override
    protected String chainIdOf(TokenKey key) {
        return key == null ? null : key.getChainId();
    }

    @Override
    protected CacheSpec toCacheSpec(String chainId, Void ctx, TokenKey key) {
        if (key == null) return null;
        String cid = key.getChainId() == null ? "" : key.getChainId().trim();
        if (cid.isBlank()) return null;
        String tokenId = key.getTokenId() == null ? "" : key.getTokenId().trim().toLowerCase(Locale.ROOT);
        if (tokenId.isBlank()) return null;
        return new CacheSpec(tokenId, RedisKeys.tokenTvl(cid, tokenId));
    }

    @Override
    protected Map<String, TokenTvlSnapshot> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, TokenTvlSnapshot.class);
    }

    @Override
    protected void redisSet(String redisKey, TokenTvlSnapshot value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, TokenTvlSnapshot> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, TokenTvlSnapshot> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        int first = Math.min(5000, Math.max(50, missIds.size() * 4));
        String q = """
            query TokenLatestTvl($tokenIds: [Bytes!]!, $first: Int!) {
              rows: tokenHourDatas(
                first: $first
                orderBy: periodStartUnix
                orderDirection: desc
                where: { token_in: $tokenIds }
              ) {
                periodStartUnix
                totalValueLockedUSD
                token { id }
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, q, Map.of("tokenIds", missIds, "first", first));
        } catch (Exception ignore) {
            return out;
        }

        JsonNode rows = data == null ? null : data.get("rows");
        if (rows == null || !rows.isArray()) return out;

        for (JsonNode n : rows) {
            JsonNode token = n.get("token");
            String id = token != null && token.hasNonNull("id") ? token.get("id").asText("") : "";
            if (id.isBlank()) continue;

            String tokenId = id.toLowerCase(Locale.ROOT);
            if (out.containsKey(tokenId)) continue; // first occurrence is the latest due to DESC ordering

            Integer periodStartUnix = n.hasNonNull("periodStartUnix") ? n.get("periodStartUnix").asInt() : null;
            BigDecimal tvlUsd = SubgraphJson.bigDecimal(n, "totalValueLockedUSD");
            out.put(tokenId, new TokenTvlSnapshot(periodStartUnix, tvlUsd));
        }

        return out;
    }
}
