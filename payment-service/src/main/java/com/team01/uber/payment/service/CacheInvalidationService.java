package com.team01.uber.payment.service;

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

    public void invalidatePaymentCaches(Long paymentId) {
        invalidatePattern("payment-service::S5-F10::*");
        invalidatePattern("payment-service::S5-F11::*");
        invalidatePattern("payment-service::payment::" + paymentId);
    }
}
