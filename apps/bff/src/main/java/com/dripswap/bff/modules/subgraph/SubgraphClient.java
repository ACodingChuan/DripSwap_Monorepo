package com.dripswap.bff.modules.subgraph;

import com.dripswap.bff.modules.gql.model.PairPayload;
import com.dripswap.bff.modules.gql.model.TokenPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Client for querying TheGraph Subgraph.
 * Supports fetching pairs, tokens, and other protocol data.
 */
@Component
public class SubgraphClient {

    private static final Logger logger = LoggerFactory.getLogger(SubgraphClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Subgraph endpoints (configure in application.yaml if needed)
    private static final Map<String, String> SUBGRAPH_ENDPOINTS = new HashMap<>();

    static {
        SUBGRAPH_ENDPOINTS.put("sepolia", "https://api.studio.thegraph.com/query/000/dripswap-sepolia/version/latest");
        SUBGRAPH_ENDPOINTS.put("scroll-sepolia", "https://api.studio.thegraph.com/query/000/dripswap-scroll/version/latest");
    }

    public SubgraphClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a GraphQL query against the subgraph.
     *
     * @param chainId Chain identifier
     * @param query   GraphQL query string
     * @return Parsed JSON response
     */
    public JsonNode query(String chainId, String query) {
        try {
            String endpoint = SUBGRAPH_ENDPOINTS.get(chainId);
            if (endpoint == null) {
                logger.warn("No subgraph endpoint configured for chain: {}", chainId);
                return null;
            }

            Map<String, Object> body = new HashMap<>();
            body.put("query", query);

            String response = restTemplate.postForObject(endpoint, body, String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            logger.error("Error querying subgraph for chain {}: {}", chainId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fetch trading pairs from subgraph.
     *
     * @param chainId Chain identifier
     * @param limit   Number of pairs to fetch
     * @return List of PairPayload
     */
    public List<PairPayload> fetchPairs(String chainId, int limit) {
        try {
            String query = String.format("""
                    {
                      pairs(first: %d, orderBy: volumeUSD, orderDirection: desc) {
                        id
                        token0 { id }
                        token1 { id }
                        reserve0
                        reserve1
                        totalSupply
                        volumeUSD
                        feesUSD
                      }
                    }
                    """, limit);

            JsonNode response = query(chainId, query);
            if (response == null || response.get("data") == null) {
                return Collections.emptyList();
            }

            List<PairPayload> pairs = new ArrayList<>();
            JsonNode pairsNode = response.get("data").get("pairs");

            for (JsonNode pairNode : pairsNode) {
                PairPayload pair = new PairPayload();
                pair.setId(pairNode.get("id").asText());
                pair.setToken0(pairNode.get("token0").get("id").asText());
                pair.setToken1(pairNode.get("token1").get("id").asText());
                pair.setReserve0(pairNode.get("reserve0").asText());
                pair.setReserve1(pairNode.get("reserve1").asText());
                pair.setTotalSupply(pairNode.get("totalSupply").asText());
                pair.setVolumeUSD(pairNode.get("volumeUSD").asText());
                pair.setFeesUSD(pairNode.get("feesUSD").asText());
                pairs.add(pair);
            }

            logger.debug("Fetched {} pairs from subgraph for chain {}", pairs.size(), chainId);
            return pairs;
        } catch (Exception e) {
            logger.error("Error fetching pairs from subgraph: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetch tokens from subgraph.
     *
     * @param chainId Chain identifier
     * @param limit   Number of tokens to fetch
     * @return List of TokenPayload
     */
    public List<TokenPayload> fetchTokens(String chainId, int limit) {
        try {
            String query = String.format("""
                    {
                      tokens(first: %d, orderBy: volumeUSD, orderDirection: desc) {
                        id
                        name
                        symbol
                        decimals
                        totalSupply
                        derivedETH
                        volumeUSD
                        feesUSD
                      }
                    }
                    """, limit);

            JsonNode response = query(chainId, query);
            if (response == null || response.get("data") == null) {
                return Collections.emptyList();
            }

            List<TokenPayload> tokens = new ArrayList<>();
            JsonNode tokensNode = response.get("data").get("tokens");

            for (JsonNode tokenNode : tokensNode) {
                TokenPayload token = new TokenPayload();
                token.setId(tokenNode.get("id").asText());
                token.setName(tokenNode.get("name").asText());
                token.setSymbol(tokenNode.get("symbol").asText());
                token.setDecimals(tokenNode.get("decimals").asInt());
                token.setTotalSupply(tokenNode.get("totalSupply").asText());
                token.setDerivedETH(tokenNode.get("derivedETH").asText());
                token.setVolumeUSD(tokenNode.get("volumeUSD").asText());
                token.setFeesUSD(tokenNode.get("feesUSD").asText());
                tokens.add(token);
            }

            logger.debug("Fetched {} tokens from subgraph for chain {}", tokens.size(), chainId);
            return tokens;
        } catch (Exception e) {
            logger.error("Error fetching tokens from subgraph: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Check subgraph health status.
     *
     * @param chainId Chain identifier
     * @return true if subgraph is healthy
     */
    public boolean isHealthy(String chainId) {
        try {
            String query = """
                    {
                      _meta {
                        hasIndexingErrors
                        block { number timestamp }
                      }
                    }
                    """;

            JsonNode response = query(chainId, query);
            if (response == null || response.get("data") == null) {
                return false;
            }

            JsonNode metaNode = response.get("data").get("_meta");
            return !metaNode.get("hasIndexingErrors").asBoolean();
        } catch (Exception e) {
            logger.error("Error checking subgraph health: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get current synced block for subgraph.
     *
     * @param chainId Chain identifier
     * @return Synced block number
     */
    public Long getSyncedBlock(String chainId) {
        try {
            String query = """
                    {
                      _meta {
                        block { number }
                      }
                    }
                    """;

            JsonNode response = query(chainId, query);
            if (response == null || response.get("data") == null) {
                return null;
            }

            return response.get("data").get("_meta").get("block").get("number").asLong();
        } catch (Exception e) {
            logger.error("Error fetching synced block: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Register custom subgraph endpoint (for testing or alternative chains).
     *
     * @param chainId  Chain identifier
     * @param endpoint Subgraph endpoint URL
     */
    public static void registerEndpoint(String chainId, String endpoint) {
        SUBGRAPH_ENDPOINTS.put(chainId, endpoint);
        logger.info("Registered subgraph endpoint for chain {}: {}", chainId, endpoint);
    }
}
