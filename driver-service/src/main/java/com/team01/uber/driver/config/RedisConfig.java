package com.team01.uber.driver.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory, RedisSerializer<Object> redisJsonSerializer) {
        RedisSerializationContext.SerializationPair<Object> jsonPair =
            RedisSerializationContext.SerializationPair.fromSerializer(redisJsonSerializer);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(jsonPair);

        // TTLs per spec §4.4.1: F1=5min search, F3=10min DTO, F5=5min JSONB,
        // F6=10min report, F9=10min combined; entity detail=15min.
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        cacheConfigs.put("driver-service::S2-F1", base.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("driver-service::S2-F3", base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("driver-service::S2-F5", base.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("driver-service::S2-F6", base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("driver-service::S2-F9", base.entryTtl(Duration.ofMinutes(10)));

        // M2 caches placeholder: S2-F10 full-text driver search, 5 min TTL
        cacheConfigs.put("driver-service::S2-F10", base.entryTtl(Duration.ofMinutes(5)));
        // M2 caches placeholder: S2-F12 driver performance dashboard, 10 min TTL
        cacheConfigs.put("driver-service::S2-F12", base.entryTtl(Duration.ofMinutes(10)));

        // Default 15 min covers CRUD GET-by-ID entity detail caches (driver, driver-document)
        RedisCacheConfiguration defaultConfig = base.entryTtl(Duration.ofMinutes(15));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory factory, RedisSerializer<Object> redisJsonSerializer) {
        RedisTemplate template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(redisJsonSerializer);
        return template;
    }

    @Bean
    public RedisSerializer<Object> redisJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build(),
            DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY
        );

        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object value) throws SerializationException {
                if (value == null) return null;
                try {
                    return mapper.writeValueAsBytes(value);
                } catch (Exception e) {
                    throw new SerializationException("Could not serialize to JSON", e);
                }
            }

            @Override
            public Object deserialize(byte[] bytes) throws SerializationException {
                if (bytes == null) return null;
                try {
                    return mapper.readValue(bytes, Object.class);
                } catch (Exception e) {
                    throw new SerializationException("Could not deserialize from JSON", e);
                }
            }
        };
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis GET failed [{}::{}]: {}", cache.getName(), key, e.getMessage());
            }
            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Redis PUT failed [{}::{}]: {}", cache.getName(), key, e.getMessage());
            }
            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis EVICT failed [{}::{}]: {}", cache.getName(), key, e.getMessage());
            }
            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Redis CLEAR failed [{}]: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
