package com.dripswap.bff.sync;

import com.dripswap.bff.entity.Token;
import com.dripswap.bff.repository.TokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Token 数据同步处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenSyncHandler {
    
    private final TokenRepository tokenRepository;
    
    /**
     * 处理从 Subgraph 查询到的 Token 数据
     * 
     * @param chainId 链 ID
     * @param tokensNode JSON 数据
     */
    @Transactional
    public void handleTokens(String chainId, JsonNode tokensNode) {
        if (tokensNode == null || !tokensNode.isArray()) {
            return;
        }
        
        List<Token> tokens = new ArrayList<>();
        
        for (JsonNode tokenNode : tokensNode) {
            try {
                Token token = parseToken(chainId, tokenNode);
                tokens.add(token);
            } catch (Exception e) {
                log.error("Failed to parse token: {}", tokenNode, e);
            }
        }
        
        if (!tokens.isEmpty()) {
            tokenRepository.saveAll(tokens);
            log.info("Saved {} tokens for chain: {}", tokens.size(), chainId);
        }
    }
    
    /**
     * 解析 Token JSON 数据
     */
    private Token parseToken(String chainId, JsonNode node) {
        Token token = new Token();
        
        token.setId(node.get("id").asText().toLowerCase());
        token.setChainId(chainId);
        token.setSymbol(node.get("symbol").asText());
        token.setName(node.get("name").asText());
        token.setDecimals(node.get("decimals").asInt());
        
        token.setTotalSupply(parseBigDecimal(node, "totalSupply"));
        token.setTradeVolume(parseBigDecimal(node, "tradeVolume"));
        token.setTradeVolumeUsd(parseBigDecimal(node, "tradeVolumeUSD"));
        token.setUntrackedVolumeUsd(parseBigDecimal(node, "untrackedVolumeUSD"));
        token.setTxCount(node.get("txCount").asLong());
        token.setTotalLiquidity(parseBigDecimal(node, "totalLiquidity"));
        token.setDerivedEth(parseBigDecimal(node, "derivedETH"));
        
        return token;
    }
    
    /**
     * 安全解析 BigDecimal
     */
    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(field.asText());
        } catch (Exception e) {
            log.warn("Failed to parse BigDecimal for field {}: {}", fieldName, field.asText());
            return BigDecimal.ZERO;
        }
    }
}
