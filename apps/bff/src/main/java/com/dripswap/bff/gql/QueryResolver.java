package com.dripswap.bff.gql;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.gql.payload.ExploreStatsSeed;
import com.dripswap.bff.gql.payload.ExploreTokenRowPayload;
import com.dripswap.bff.sync.SubgraphClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphqlErrorException;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Controller
@RequiredArgsConstructor
public class QueryResolver {

    private static final long TTL_TOKEN_LIST_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final SubgraphProperties subgraphProperties;
    private final SubgraphClient subgraphClient;

    /**
     * Explore protocol stats (6.2).
     *
     * <p>We return a lightweight seed object and resolve fields via {@link ExploreStatsFieldResolver}
     * + DataLoader (with Redis read-through caching).</p>
     */
    @QueryMapping
    public ExploreStatsSeed exploreStats(
            @Argument String chainId,
            @Argument Integer days,
            DataFetchingEnvironment env
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int windowDays = days == null ? 30 : Math.max(1, Math.min(days, 90));

        boolean needsSeries = env != null && (env.getSelectionSet().contains("tvlSeries") || env.getSelectionSet().contains("volumeSeries"));
        if (env != null) {
            env.getGraphQlContext().put("exploreStats:needsSeries:" + normalizedChainId + ":" + windowDays, needsSeries);
        }
        return new ExploreStatsSeed(normalizedChainId, windowDays);
    }

    /**
     * Explore tokens (6.3).
     *
     * <p>Main query stays lean: only returns base token list (id/symbol/name/decimals/totalSupply/derivedETH).
     * Derived fields are resolved via {@link ExploreTokenRowFieldResolver} + DataLoader (with Redis 2nd-level cache).</p>
     */
    @QueryMapping
    public List<ExploreTokenRowPayload> exploreTokens(
            @Argument String chainId,
            @Argument Integer limit,
            @Argument String search,
            @Argument ExploreTokenSort sort
    ) {
        String normalizedChainId = normalizeChainId(chainId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));

        // Redis key: ds:v2:{chain}:tokens:list:{limit}:{searchHash}
        String searchKey = (search == null || search.trim().isEmpty())
                ? "all"
                : Integer.toHexString(search.trim().toLowerCase(Locale.ROOT).hashCode());
        String redisKey = String.format("ds:v2:%s:tokens:list:%d:%s", normalizedChainId, size, searchKey);

        // 1) Try Redis first
        String cached = safeRedisGet(redisKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                return objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExploreTokenRowPayload.class)
                );
            } catch (Exception e) {
                log.warn("Redis deserialization failed for key: {}, fallback to subgraph", redisKey, e);
            }
        }

        // 2) Redis miss -> query Goldsky subgraph
        String endpoint = requireSubgraphEndpoint(normalizedChainId);

        // Fetch a larger page and filter in-memory (avoid complex OR filters on subgraph).
        int fetchSize = Math.min(500, Math.max(200, size * 5));
        String query = """
            query ExploreTokens($first: Int!) {
              tokens: tokens(first: $first, orderBy: tradeVolumeUSD, orderDirection: desc) {
                id
                symbol
                name
                decimals
                totalSupply
                derivedETH
              }
            }
            """;

        JsonNode data;
        try {
            data = subgraphClient.query(endpoint, query, Map.of("first", fetchSize));
        } catch (Exception e) {
            log.warn("exploreTokens subgraph failed: chainId={}, err={}", normalizedChainId, e.getMessage());
            return List.of();
        }

        JsonNode tokensNode = data == null ? null : data.get("tokens");
        if (tokensNode == null || !tokensNode.isArray()) {
            return List.of();
        }

        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<ExploreTokenRowPayload> result = new ArrayList<>();
        for (JsonNode n : tokensNode) {
            String id = n.hasNonNull("id") ? n.get("id").asText("") : "";
            if (id.isBlank()) continue;

            String symbol = n.hasNonNull("symbol") ? n.get("symbol").asText("") : "";
            String name = n.hasNonNull("name") ? n.get("name").asText("") : "";

            if (!q.isEmpty()) {
                String idLc = id.toLowerCase(Locale.ROOT);
                if (!idLc.contains(q)
                        && !symbol.toLowerCase(Locale.ROOT).contains(q)
                        && !name.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
            }

            Integer decimals = parseIntField(n, "decimals");
            BigDecimal totalSupply = parseBigDecimalField(n, "totalSupply");
            BigDecimal derivedEth = parseBigDecimalField(n, "derivedETH");

            result.add(ExploreTokenRowPayload.builder()
                    .id(id)
                    .chainId(normalizedChainId)
                    .symbol(symbol)
                    .name(name)
                    .decimals(decimals == null ? 0 : decimals)
                    .totalSupply(totalSupply == null ? BigDecimal.ZERO : totalSupply)
                    .derivedETH(derivedEth == null ? BigDecimal.ZERO : derivedEth)
                    .build());

            if (result.size() >= size) break;
        }

        if (result.isEmpty()) {
            return List.of();
        }

        // 3) Write base list back to Redis (derived fields are computed via field resolvers)
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json, java.time.Duration.ofSeconds(TTL_TOKEN_LIST_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to cache exploreTokens to Redis: {}", redisKey, e);
        }

        return result;
    }

    private String safeRedisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis GET failed: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    private String normalizeChainId(String chainId) {
        if (chainId == null) return "sepolia";
        String v = chainId.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "11155111", "sepolia" -> "sepolia";
            case "534351", "scroll-sepolia", "scroll_sepolia", "scroll sepolia" -> "scroll-sepolia";
            default -> v;
        };
    }

    private String resolveSubgraphEndpoint(String normalizedChainId) {
        if (subgraphProperties == null || subgraphProperties.getChains() == null) return null;
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (chain == null) continue;
            if (!Objects.equals(chain.getId(), normalizedChainId)) continue;
            if (!chain.isEnabled()) continue;
            return chain.getEndpointV2();
        }
        return null;
    }

    private String requireSubgraphEndpoint(String normalizedChainId) {
        String endpoint = resolveSubgraphEndpoint(normalizedChainId);
        if (endpoint == null || endpoint.isBlank()) {
            throw GraphqlErrorException.newErrorException()
                    .message("Unsupported chainId: " + normalizedChainId)
                    .errorClassification(ErrorType.BAD_REQUEST)
                    .build();
        }
        return endpoint;
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

    private Integer parseIntField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) return null;
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) return null;
        try {
            if (v.isInt()) return v.intValue();
            if (v.isNumber()) return v.numberValue().intValue();
            String text = v.asText(null);
            if (text == null || text.isBlank()) return null;
            return new java.math.BigInteger(text).intValue();
        } catch (Exception e) {
            return null;
        }
    }
}

