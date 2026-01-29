package com.dripswap.bff.service;

import java.time.Duration;

import com.dripswap.bff.config.FaucetV2Properties;
import com.dripswap.bff.repository.ChainConfig;
import com.dripswap.bff.repository.FaucetV2ChainConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class FaucetV2ChainConfigCacheService {
    private static final String KEY_PREFIX = "ds:v2:faucetv2:chaincfg:";

    private final FaucetV2Properties props;
    private final FaucetV2ChainConfigRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public FaucetV2ChainConfigCacheService(
            FaucetV2Properties props,
            FaucetV2ChainConfigRepository repo,
            StringRedisTemplate redis,
            ObjectMapper objectMapper
    ) {
        this.props = props;
        this.repo = repo;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public ChainConfig load(long chainId) {
        String key = KEY_PREFIX + chainId;
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                return objectMapper.readValue(cached, ChainConfig.class);
            } catch (Exception ignored) {
                redis.delete(key);
            }
        }

        ChainConfig cfg = repo.findByChainId(chainId);
        if (cfg == null) return null;
        int ttlSeconds = Math.max(60, props.getCache().getStateTtlSeconds());
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(cfg), Duration.ofSeconds(ttlSeconds));
        } catch (Exception ignored) {
            // cache is best-effort
        }
        return cfg;
    }
}
