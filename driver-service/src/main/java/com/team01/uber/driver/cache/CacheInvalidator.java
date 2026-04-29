package com.team01.uber.driver.cache;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CacheInvalidator {

    private final RedisTemplate<String, Object> redisTemplate;

    @SuppressWarnings("unchecked")
    public CacheInvalidator(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void deleteKey(String fullKey) {
        redisTemplate.delete(fullKey);
    }

    public void deleteEntity(String entity, Object id) {
        redisTemplate.delete("driver-service::" + entity + "::" + id);
    }

    public void deleteByPattern(String pattern) {
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
    }
}
