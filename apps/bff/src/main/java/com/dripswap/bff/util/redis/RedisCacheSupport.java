package com.dripswap.bff.util.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Cross-request Redis caching helpers (read-through) for DataLoaders.
 *
 * <p>DataLoader provides request-scoped caching; Redis here provides cross-request caching.</p>
 */
@Component
@RequiredArgsConstructor
public class RedisCacheSupport {

    private static final String NIL = "__nil__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, BigDecimal> mgetBigDecimal(List<String> ids, Function<String, String> redisKeyFn) {
        try {
            if (ids == null || ids.isEmpty()) return Map.of();
            List<String> safeIds = ids.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
            if (safeIds.isEmpty()) return Map.of();

            List<String> keys = safeIds.stream().map(redisKeyFn).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            Map<String, BigDecimal> out = new HashMap<>();
            for (int i = 0; i < safeIds.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(safeIds.get(i), new BigDecimal(raw));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void setBigDecimal(String redisKey, BigDecimal value, long ttlSeconds) {
        try {
            if (redisKey == null || redisKey.isBlank()) return;
            String val = value == null ? NIL : value.toPlainString();
            redisTemplate.opsForValue().set(redisKey, val, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception ignore) {
        }
    }

    public <K, V> Map<K, V> mgetJson(List<K> ids, Function<K, String> redisKeyFn, Class<V> clazz) {
        try {
            if (ids == null || ids.isEmpty()) return Map.of();
            List<K> safeIds = ids.stream().filter(Objects::nonNull).toList();
            if (safeIds.isEmpty()) return Map.of();

            List<String> keys = safeIds.stream().map(redisKeyFn).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            Map<K, V> out = new HashMap<>();
            for (int i = 0; i < safeIds.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(safeIds.get(i), objectMapper.readValue(raw, clazz));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public <K, E> Map<K, List<E>> mgetJsonList(List<K> ids, Function<K, String> redisKeyFn, Class<E> elementClass) {
        try {
            if (ids == null || ids.isEmpty()) return Map.of();
            List<K> safeIds = ids.stream().filter(Objects::nonNull).toList();
            if (safeIds.isEmpty()) return Map.of();

            List<String> keys = safeIds.stream().map(redisKeyFn).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) return Map.of();

            var type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementClass);

            Map<K, List<E>> out = new HashMap<>();
            for (int i = 0; i < safeIds.size(); i++) {
                String raw = values.size() > i ? values.get(i) : null;
                if (raw == null || raw.isBlank() || NIL.equals(raw)) continue;
                try {
                    out.put(safeIds.get(i), objectMapper.readValue(raw, type));
                } catch (Exception ignore) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void setJson(String redisKey, Object value, long ttlSeconds) {
        try {
            if (redisKey == null || redisKey.isBlank()) return;
            String val = value == null ? NIL : objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(redisKey, val, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception ignore) {
        }
    }
}
