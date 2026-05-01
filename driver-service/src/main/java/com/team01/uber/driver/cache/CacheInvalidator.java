package com.team01.uber.driver.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheInvalidator(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void deleteKey(String fullKey) {
        try {
            redisTemplate.delete(fullKey);
        } catch (Exception e) {
            log.warn("Redis DELETE failed for key {}: {}", fullKey, e.getMessage());
        }
    }

    public void deleteEntity(String entity, Object id) {
        deleteKey("driver-service::" + entity + "::" + id);
    }

    public void deleteByPattern(String pattern) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis SCAN+DEL failed for pattern {}: {}", pattern, e.getMessage());
        }
    }
}
