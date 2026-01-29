package com.dripswap.bff.gql.dataloader.loader.token;

import com.dripswap.bff.gql.dataloader.core.AbstractRedisSubgraphBatchLoader;
import com.dripswap.bff.gql.dataloader.core.CacheSpec;
import com.dripswap.bff.subgraph.SubgraphEndpointResolver;
import com.dripswap.bff.subgraph.SubgraphJson;
import com.dripswap.bff.gql.dto.TokenDayStats;
import com.dripswap.bff.gql.dto.TokenKey;
import com.dripswap.bff.subgraph.SubgraphClient;
import com.dripswap.bff.util.redis.RedisCacheSupport;
import com.dripswap.bff.util.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TokenDayStatsLoader extends AbstractRedisSubgraphBatchLoader<TokenKey, Void, TokenDayStats> {

    private static final long TTL_SECONDS = 60;

    private final RedisCacheSupport redis;

    public TokenDayStatsLoader(SubgraphClient subgraphClient, SubgraphEndpointResolver endpointResolver, RedisCacheSupport redis) {
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
        return new CacheSpec(tokenId, RedisKeys.tokenDayStats(cid, tokenId));
    }

    @Override
    protected Map<String, TokenDayStats> redisMget(List<String> ids, java.util.function.Function<String, String> redisKeyFn) {
        return redis.mgetJson(ids, redisKeyFn, TokenDayStats.class);
    }

    @Override
    protected void redisSet(String redisKey, TokenDayStats value, long ttlSeconds) {
        redis.setJson(redisKey, value, ttlSeconds);
    }

    @Override
    protected long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Override
    protected Map<String, TokenDayStats> fetchFromSubgraph(String endpoint, String chainId, Void ctx, List<String> missIds) {
        Map<String, TokenDayStats> out = new HashMap<>();
        if (missIds == null || missIds.isEmpty()) return out;

        int first = Math.min(5000, Math.max(50, missIds.size() * 3));
        String q = """
            query TokenDayStats($tokenIds: [Bytes!]!, $first: Int!) {
              rows: tokenDayDatas(
                first: $first
                orderBy: date
                orderDirection: desc
                where: { token_in: $tokenIds }
              ) {
                date
                dailyVolumeUSD
                dailyTxns
                priceUSD
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

        Map<String, List<JsonNode>> byToken = new HashMap<>();
        for (JsonNode n : rows) {
            JsonNode token = n.get("token");
            String id = token != null && token.hasNonNull("id") ? token.get("id").asText("") : "";
            if (id.isBlank()) continue;
            byToken.computeIfAbsent(id.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(n);
        }

        for (String tokenId : missIds) {
            if (tokenId == null || tokenId.isBlank()) continue;
            List<JsonNode> list = byToken.get(tokenId.toLowerCase(Locale.ROOT));
            if (list == null || list.isEmpty()) continue;

            JsonNode latest = list.get(0);
            int latestDate = latest.hasNonNull("date") ? latest.get("date").asInt() : 0;
            BigDecimal latestPrice = SubgraphJson.bigDecimal(latest, "priceUSD");
            BigDecimal latestVol = SubgraphJson.bigDecimal(latest, "dailyVolumeUSD");
            BigDecimal latestTxns = SubgraphJson.bigDecimal(latest, "dailyTxns");

            BigDecimal prevPrice = null;
            int targetDate = latestDate - 86_400;
            for (int i = 1; i < list.size(); i++) {
                JsonNode candidate = list.get(i);
                int d = candidate.hasNonNull("date") ? candidate.get("date").asInt() : 0;
                if (d <= targetDate) {
                    prevPrice = SubgraphJson.bigDecimal(candidate, "priceUSD");
                    break;
                }
            }

            out.put(tokenId.toLowerCase(Locale.ROOT), new TokenDayStats(latestDate, latestPrice, prevPrice, latestVol, latestTxns));
        }

        return out;
    }
}
