package com.team01.uber.payment.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.time.Duration;
import java.util.Map;

@Configuration
public class RedisConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
                log.warn("Cache get failed for key {}: {}", key, e.getMessage());
            }
            @Override
            public void handleCachePutError(RuntimeException e, org.springframework.cache.Cache cache, Object key, Object value) {
                log.warn("Cache put failed for key {}: {}", key, e.getMessage());
            }
            @Override
            public void handleCacheEvictError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
                log.warn("Cache evict failed for key {}: {}", key, e.getMessage());
            }
            @Override
            public void handleCacheClearError(RuntimeException e, org.springframework.cache.Cache cache) {
                log.warn("Cache clear failed: {}", e.getMessage());
            }
        };
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
                ObjectMapper.DefaultTyping.EVERYTHING,
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

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                        RedisSerializer<Object> redisJsonSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(redisJsonSerializer);
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                           RedisSerializer<Object> redisJsonSerializer) {
        RedisSerializationContext.SerializationPair<Object> jsonPair =
                RedisSerializationContext.SerializationPair.fromSerializer(redisJsonSerializer);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(jsonPair)
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = new java.util.HashMap<>();
        cacheConfigs.put("payment-service::S5-F1",          base.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("payment-service::S5-F3",          base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("payment-service::S5-F6",          base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("payment-service::S5-F8",          base.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put("payment-service::S5-F9",          base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("payment-service::S5-F10",         base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("payment-service::S5-F11",         base.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("payment-service::payment",         base.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put("payment-service::coupon",          base.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put("payment-service::payment-coupon",  base.entryTtl(Duration.ofMinutes(15)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(15)))
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
