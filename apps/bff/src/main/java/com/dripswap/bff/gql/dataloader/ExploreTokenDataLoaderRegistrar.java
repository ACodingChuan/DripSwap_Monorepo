package com.dripswap.bff.gql.dataloader;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.sync.SubgraphClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dripswap.bff.gql.model.TokenDayStats;
import com.dripswap.bff.gql.model.TokenHourStats;
import com.dripswap.bff.gql.model.TokenKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dataloader.BatchLoaderEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explore token DataLoader registration (Spring GraphQL).
 *
 * <p>We keep {@link com.dripswap.bff.gql.QueryResolver#exploreTokens} lean (base token list only)
 * and compute expensive fields via GraphQL field resolvers + DataLoader batch fetches.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreTokenDataLoaderRegistrar {

    public static final String DL_ETH_PRICE = "ethPrice";
    public static final String DL_TOKEN_DAY_STATS = "tokenDayStats";
    public static final String DL_TOKEN_HOUR_STATS = "tokenHourStats";

    // Keep these short because token changes/volume are time-sensitive.
    private static final long TTL_ETH_PRICE_SECONDS = 60;
    private static final long TTL_TOKEN_DAY_STATS_SECONDS = 60;
    private static final long TTL_TOKEN_HOUR_STATS_SECONDS = 60;
    private static final String NIL = "__nil__";

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final SubgraphProperties subgraphProperties;
    private final SubgraphClient subgraphClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    public void register() {
        // Spring Boot auto-wires BatchLoaderRegistry into the execution service. Register by name so
        // DataFetchingEnvironment#getDataLoader("...") can locate it.
        batchLoaderRegistry.<String, BigDecimal>forName(DL_ETH_PRICE)
                .withName(DL_ETH_PRICE)
                .registerMappedBatchLoader(this::loadEthPrice);

        batchLoaderRegistry.<TokenKey, TokenDayStats>forName(DL_TOKEN_DAY_STATS)
                .withName(DL_TOKEN_DAY_STATS)
                .registerMappedBatchLoader(this::loadTokenDayStats);

        batchLoaderRegistry.<TokenKey, TokenHourStats>forName(DL_TOKEN_HOUR_STATS)
                .withName(DL_TOKEN_HOUR_STATS)
                .registerMappedBatchLoader(this::loadTokenHourStats);
    }

    private Mono<Map<String, BigDecimal>> loadEthPrice(Set<String> chainIds, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> {
            Map<String, BigDecimal> out = new HashMap<>();
            Map<String, BigDecimal> cached = redisGetEthPrices(chainIds);
            out.putAll(cached);

            for (String chainId : chainIds) {
                if (chainId == null || chainId.isBlank()) continue;
                if (out.containsKey(chainId)) continue; // Redis hit

                String endpoint = resolveSubgraphEndpoint(chainId);
                if (endpoint == null || endpoint.isBlank()) continue;

                try {
                    String q = """
                        query LatestEthPrice {
                          latestBundle: bundles(first: 1, orderBy: timestamp, orderDirection: desc) {
                            ethPrice
                          }
                        }
                        """;

                    JsonNode data = subgraphClient.query(endpoint, q, Map.of());
                    BigDecimal ethPrice = BigDecimal.ZERO;
                    JsonNode bundles = data == null ? null : data.get("latestBundle");
                    if (bundles != null && bundles.isArray() && !bundles.isEmpty()) {
                        ethPrice = parseBigDecimalField(bundles.get(0), "ethPrice");
                    }
                    out.put(chainId, ethPrice);
                    redisSetEthPrice(chainId, ethPrice);
                } catch (Exception e) {
                    log.warn("EthPrice DataLoader failed: chainId={}, err={}", chainId, e.getMessage());
                }
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<TokenKey, TokenDayStats>> loadTokenDayStats(Set<TokenKey> keys, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> {
            Map<TokenKey, TokenDayStats> out = new HashMap<>();
            if (keys == null || keys.isEmpty()) return out;

            Map<String, List<TokenKey>> byChain = new HashMap<>();
            for (TokenKey key : keys) {
                if (key == null || key.getChainId() == null || key.getTokenId() == null) continue;
                byChain.computeIfAbsent(key.getChainId(), k -> new ArrayList<>()).add(key);
            }

            for (Map.Entry<String, List<TokenKey>> entry : byChain.entrySet()) {
                String chainId = entry.getKey();
                List<TokenKey> chainKeys = entry.getValue();
                String endpoint = resolveSubgraphEndpoint(chainId);
                if (endpoint == null || endpoint.isBlank()) continue;

                List<String> tokenIds = chainKeys.stream()
                        .map(TokenKey::getTokenId)
                        .filter(Objects::nonNull)
                        .map(id -> id.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
                if (tokenIds.isEmpty()) continue;

                // 1) Redis lookup per tokenId (read-through)
                Map<String, TokenDayStats> cachedByToken = redisGetTokenDayStats(chainId, tokenIds);
                for (TokenKey key : chainKeys) {
                    if (key == null || key.getTokenId() == null) continue;
                    TokenDayStats cached = cachedByToken.get(key.getTokenId().toLowerCase(Locale.ROOT));
                    if (cached != null) {
                        out.put(key, cached);
                    }
                }

                List<String> missTokenIds = tokenIds.stream()
                        .filter(t -> !cachedByToken.containsKey(t))
                        .toList();
                if (missTokenIds.isEmpty()) {
                    continue;
                }

                int first = Math.min(5000, Math.max(50, missTokenIds.size() * 3));
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
                    data = subgraphClient.query(endpoint, q, Map.of("tokenIds", missTokenIds, "first", first));
                } catch (Exception e) {
                    log.warn("TokenDayStats DataLoader failed: chainId={}, err={}", chainId, e.getMessage());
                    continue;
                }

                JsonNode rows = data == null ? null : data.get("rows");
                if (rows == null || !rows.isArray()) continue;

                Map<String, List<JsonNode>> byToken = new HashMap<>();
                for (JsonNode n : rows) {
                    JsonNode token = n.get("token");
                    String id = token != null && token.hasNonNull("id") ? token.get("id").asText("") : "";
                    if (id.isBlank()) continue;
                    byToken.computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(n);
                }

                Map<String, TokenDayStats> statsByToken = new HashMap<>();
                for (String tokenId : missTokenIds) {
                    List<JsonNode> list = byToken.get(tokenId);
                    if (list == null || list.isEmpty()) continue;

                    JsonNode latest = list.get(0);
                    int latestDate = latest.hasNonNull("date") ? latest.get("date").asInt() : 0;
                    BigDecimal latestPrice = parseBigDecimalField(latest, "priceUSD");
                    BigDecimal latestVol = parseBigDecimalField(latest, "dailyVolumeUSD");
                    BigDecimal latestTxns = parseBigDecimalField(latest, "dailyTxns");

                    BigDecimal prevPrice = null;
                    int targetDate = latestDate - 86_400;
                    for (int i = 1; i < list.size(); i++) {
                        JsonNode candidate = list.get(i);
                        int d = candidate.hasNonNull("date") ? candidate.get("date").asInt() : 0;
                        if (d <= targetDate) {
                            prevPrice = parseBigDecimalField(candidate, "priceUSD");
                            break;
                        }
                    }

                    TokenDayStats stats = new TokenDayStats(latestDate, latestPrice, prevPrice, latestVol, latestTxns);
                    statsByToken.put(tokenId, stats);
                    redisSetTokenDayStats(chainId, tokenId, stats);
                }

                for (TokenKey key : chainKeys) {
                    if (key == null) continue;
                    String id = key.getTokenId() == null ? "" : key.getTokenId().toLowerCase(Locale.ROOT);
                    TokenDayStats stats = out.get(key);
                    if (stats == null) {
                        stats = statsByToken.get(id);
                    }
                    if (stats != null) {
                        out.put(key, stats);
                    }
                }
            }

            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<TokenKey, TokenHourStats>> loadTokenHourStats(Set<TokenKey> keys, BatchLoaderEnvironment env) {
        return Mono.fromCallable(() -> {
            Map<TokenKey, TokenHourStats> out = new HashMap<>();
            if (keys == null || keys.isEmpty()) return out;

            Map<String, List<TokenKey>> byChain = new HashMap<>();
            for (TokenKey key : keys) {
                if (key == null || key.getChainId() == null || key.getTokenId() == null) continue;
                byChain.computeIfAbsent(key.getChainId(), k -> new ArrayList<>()).add(key);
            }

            for (Map.Entry<String, List<TokenKey>> entry : byChain.entrySet()) {
                String chainId = entry.getKey();
                List<TokenKey> chainKeys = entry.getValue();
                String endpoint = resolveSubgraphEndpoint(chainId);
                if (endpoint == null || endpoint.isBlank()) continue;

                List<String> tokenIds = chainKeys.stream()
                        .map(TokenKey::getTokenId)
                        .filter(Objects::nonNull)
                        .map(id -> id.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
                if (tokenIds.isEmpty()) continue;

                // 1) Redis lookup (read-through)
                Map<String, TokenHourStats> cachedByToken = redisGetTokenHourStats(chainId, tokenIds);
                for (TokenKey key : chainKeys) {
                    if (key == null || key.getTokenId() == null) continue;
                    TokenHourStats cached = cachedByToken.get(key.getTokenId().toLowerCase(Locale.ROOT));
                    if (cached != null) {
                        out.put(key, cached);
                    }
                }

                List<String> missTokenIds = tokenIds.stream()
                        .filter(t -> !cachedByToken.containsKey(t))
                        .toList();
                if (missTokenIds.isEmpty()) {
                    continue;
                }

                int first = Math.min(5000, Math.max(50, missTokenIds.size() * 24));
                String q = """
                    query TokenHourStats($tokenIds: [Bytes!]!, $first: Int!) {
                      rows: tokenHourDatas(
                        first: $first
                        orderBy: periodStartUnix
                        orderDirection: desc
                        where: { token_in: $tokenIds }
                      ) {
                        periodStartUnix
                        open
                        close
                        volumeUSD
                        token { id }
                      }
                    }
                    """;

                JsonNode data;
                try {
                    data = subgraphClient.query(endpoint, q, Map.of("tokenIds", missTokenIds, "first", first));
                } catch (Exception e) {
                    log.warn("TokenHourStats DataLoader failed: chainId={}, err={}", chainId, e.getMessage());
                    continue;
                }

                JsonNode rows = data == null ? null : data.get("rows");
                if (rows == null || !rows.isArray()) continue;

                Map<String, TokenHourStats> statsByToken = new HashMap<>();
                for (JsonNode n : rows) {
                    JsonNode token = n.get("token");
                    String id = token != null && token.hasNonNull("id") ? token.get("id").asText("") : "";
                    if (id.isBlank()) continue;
                    String tokenId = id.toLowerCase(Locale.ROOT);
                    if (statsByToken.containsKey(tokenId)) continue; // first occurrence is the latest

                    Integer periodStartUnix = n.hasNonNull("periodStartUnix") ? n.get("periodStartUnix").asInt() : null;
                    TokenHourStats stats = new TokenHourStats(
                            periodStartUnix,
                            parseBigDecimalField(n, "open"),
                            parseBigDecimalField(n, "close"),
                            parseBigDecimalField(n, "volumeUSD")
                    );
                    statsByToken.put(
                            tokenId,
                            stats
                    );
                    redisSetTokenHourStats(chainId, tokenId, stats);
                }

                for (TokenKey key : chainKeys) {
                    if (key == null) continue;
                    String id = key.getTokenId() == null ? "" : key.getTokenId().toLowerCase(Locale.ROOT);
                    TokenHourStats stats = out.get(key);
                    if (stats == null) {
                        stats = statsByToken.get(id);
                    }
                    if (stats != null) {
                        out.put(key, stats);
                    }
                }
            }

            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, BigDecimal> redisGetEthPrices(Set<String> chainIds) {
        try {
            List<String> ids = chainIds.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
            if (ids.isEmpty()) return Map.of();

            List<String> keys = ids.stream().map(this::redisEthPriceKey).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            Map<String, BigDecimal> out = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(ids.get(i), new BigDecimal(raw));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void redisSetEthPrice(String chainId, BigDecimal ethPrice) {
        try {
            if (chainId == null || chainId.isBlank()) return;
            String key = redisEthPriceKey(chainId);
            String val = ethPrice == null ? NIL : ethPrice.toPlainString();
            redisTemplate.opsForValue().set(key, val, java.time.Duration.ofSeconds(TTL_ETH_PRICE_SECONDS));
        } catch (Exception ignore) {
        }
    }

    private Map<String, TokenDayStats> redisGetTokenDayStats(String chainId, List<String> tokenIds) {
        try {
            if (chainId == null || chainId.isBlank() || tokenIds == null || tokenIds.isEmpty()) return Map.of();
            List<String> keys = tokenIds.stream().map(id -> redisTokenDayStatsKey(chainId, id)).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            Map<String, TokenDayStats> out = new HashMap<>();
            for (int i = 0; i < tokenIds.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(tokenIds.get(i), objectMapper.readValue(raw, TokenDayStats.class));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void redisSetTokenDayStats(String chainId, String tokenId, TokenDayStats stats) {
        try {
            if (chainId == null || chainId.isBlank() || tokenId == null || tokenId.isBlank()) return;
            String key = redisTokenDayStatsKey(chainId, tokenId);
            String val = stats == null ? NIL : objectMapper.writeValueAsString(stats);
            redisTemplate.opsForValue().set(key, val, java.time.Duration.ofSeconds(TTL_TOKEN_DAY_STATS_SECONDS));
        } catch (Exception ignore) {
        }
    }

    private Map<String, TokenHourStats> redisGetTokenHourStats(String chainId, List<String> tokenIds) {
        try {
            if (chainId == null || chainId.isBlank() || tokenIds == null || tokenIds.isEmpty()) return Map.of();
            List<String> keys = tokenIds.stream().map(id -> redisTokenHourStatsKey(chainId, id)).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            Map<String, TokenHourStats> out = new HashMap<>();
            for (int i = 0; i < tokenIds.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(tokenIds.get(i), objectMapper.readValue(raw, TokenHourStats.class));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void redisSetTokenHourStats(String chainId, String tokenId, TokenHourStats stats) {
        try {
            if (chainId == null || chainId.isBlank() || tokenId == null || tokenId.isBlank()) return;
            String key = redisTokenHourStatsKey(chainId, tokenId);
            String val = stats == null ? NIL : objectMapper.writeValueAsString(stats);
            redisTemplate.opsForValue().set(key, val, java.time.Duration.ofSeconds(TTL_TOKEN_HOUR_STATS_SECONDS));
        } catch (Exception ignore) {
        }
    }

    private String redisEthPriceKey(String chainId) {
        return String.format("ds:v2:%s:bundle:ethPrice", chainId);
    }

    private String redisTokenDayStatsKey(String chainId, String tokenId) {
        return String.format("ds:v2:%s:token:%s:dayStats", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    private String redisTokenHourStatsKey(String chainId, String tokenId) {
        return String.format("ds:v2:%s:token:%s:hourStats", chainId, tokenId.toLowerCase(Locale.ROOT));
    }

    private String resolveSubgraphEndpoint(String chainId) {
        if (subgraphProperties == null || subgraphProperties.getChains() == null) return null;
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (chain == null) continue;
            if (!Objects.equals(chain.getId(), chainId)) continue;
            if (!chain.isEnabled()) continue;
            return chain.getEndpointV2();
        }
        return null;
    }

    private BigDecimal parseBigDecimalField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return BigDecimal.ZERO;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return BigDecimal.ZERO;
        try {
            if (v.isNumber()) return v.decimalValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(text);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
