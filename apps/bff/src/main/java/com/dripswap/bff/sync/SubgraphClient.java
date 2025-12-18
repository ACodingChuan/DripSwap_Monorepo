package com.dripswap.bff.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Subgraph HTTP 客户端
 * 负责调用 The Graph API 查询数据
 */
@Slf4j
@Component
public class SubgraphClient {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public SubgraphClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 执行 GraphQL 查询
     * 
     * @param endpoint Subgraph endpoint URL
     * @param query GraphQL 查询语句
     * @param variables 查询变量
     * @return 查询结果 JSON
     */
    public JsonNode query(String endpoint, String query, Map<String, Object> variables) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);
            if (variables != null && !variables.isEmpty()) {
                requestBody.put("variables", variables);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                request,
                String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 检查 GraphQL 错误
                if (root.has("errors")) {
                    log.error("GraphQL errors: {}", root.get("errors"));
                    throw new RuntimeException("GraphQL query failed: " + root.get("errors"));
                }
                
                return root.get("data");
            } else {
                throw new RuntimeException("HTTP request failed: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Subgraph query failed: endpoint={}, error={}", endpoint, e.getMessage(), e);
            throw new RuntimeException("Subgraph query failed", e);
        }
    }
    
    /**
     * 分页查询
     * 
     * @param endpoint Subgraph endpoint
     * @param query GraphQL 查询 (需包含 $first 和 $skip 变量)
     * @param first 每页数量
     * @param skip 跳过数量
     * @return 查询结果
     */
    public JsonNode queryWithPagination(String endpoint, String query, int first, int skip) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("first", first);
        variables.put("skip", skip);
        return query(endpoint, query, variables);
    }
}
