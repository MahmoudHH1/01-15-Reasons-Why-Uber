package com.team01.uber.driver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CacheInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheInvalidationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void invalidatePattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis invalidation failed for pattern {}: {}", pattern, e.getMessage());
        }
    }

    public void invalidateDriverIndexCaches(Long driverId) {
        invalidatePattern("driver-service::S2-F10::*");
        invalidatePattern("driver-service::S2-F12::" + driverId);
        invalidatePattern("driver-service::driver::" + driverId);
    }
}
