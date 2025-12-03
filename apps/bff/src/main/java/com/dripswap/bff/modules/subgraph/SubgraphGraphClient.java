package com.dripswap.bff.modules.subgraph;

import com.dripswap.bff.config.SubgraphProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class SubgraphGraphClient {

    private static final Logger log = LoggerFactory.getLogger(SubgraphGraphClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SubgraphProperties subgraphProperties;

    public SubgraphGraphClient(RestTemplate restTemplate, ObjectMapper objectMapper, SubgraphProperties subgraphProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.subgraphProperties = subgraphProperties;
    }

    public Optional<JsonNode> query(String chainId, String query, Map<String, Object> variables) {
        log.debug("[SubgraphGraphClient] Query request for chain: {}", chainId);
        try {
            String endpoint = subgraphProperties.getChains().stream()
                    .filter(c -> c.isEnabled() && chainId.equalsIgnoreCase(c.getId()))
                    .map(SubgraphProperties.ChainConfig::getEndpoint)
                    .findFirst()
                    .orElse(null);
            if (endpoint == null || endpoint.isBlank()) {
                log.warn("[SubgraphGraphClient] Subgraph endpoint missing or disabled for chain {}", chainId);
                log.warn("[SubgraphGraphClient] Available chains: {}", subgraphProperties.getChains().stream()
                        .map(c -> c.getId() + "(enabled=" + c.isEnabled() + ", endpoint=" + c.getEndpoint() + ")")
                        .toList());
                return Optional.empty();
            }

            log.info("[SubgraphGraphClient] Querying endpoint: {}", endpoint);
            log.debug("[SubgraphGraphClient] Variables: {}", variables);
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            if (variables != null && !variables.isEmpty()) {
                payload.put("variables", variables);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            log.debug("[SubgraphGraphClient] Sending HTTP POST request...");
            String response = restTemplate.postForObject(endpoint, request, String.class);
            if (response == null) {
                log.warn("[SubgraphGraphClient] Received null response from subgraph");
                return Optional.empty();
            }
            
            log.debug("[SubgraphGraphClient] Response received, length: {}", response.length());
            JsonNode node = objectMapper.readTree(response);
            
            if (node.has("errors")) {
                log.error("[SubgraphGraphClient] Subgraph returned errors for chain {}: {}", chainId, node.get("errors").toString());
            } else {
                log.info("[SubgraphGraphClient] Query successful for chain: {}", chainId);
            }
            
            return Optional.of(node);
        } catch (Exception e) {
            log.error("[SubgraphGraphClient] Subgraph query failed for chain {}: {}", chainId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<JsonNode> query(String chainId, String query) {
        return query(chainId, query, Map.of());
    }
}
