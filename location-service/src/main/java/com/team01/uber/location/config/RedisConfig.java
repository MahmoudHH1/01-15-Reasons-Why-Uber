package com.team01.uber.location.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory, RedisSerializer<Object> redisJsonSerializer) {
        RedisSerializationContext.SerializationPair<Object> jsonPair =
            RedisSerializationContext.SerializationPair.fromSerializer(redisJsonSerializer);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(jsonPair);

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        // M1 feature caches (S4: F1, F3, F5, F6, F8, F9)
        cacheConfigs.put("location-service::S4-F1", base.entryTtl(Duration.ofMinutes(5)));   // search
        cacheConfigs.put("location-service::S4-F3", base.entryTtl(Duration.ofMinutes(10)));  // DTO
        cacheConfigs.put("location-service::S4-F5", base.entryTtl(Duration.ofMinutes(5)));   // JSONB query
        cacheConfigs.put("location-service::S4-F6", base.entryTtl(Duration.ofMinutes(10)));  // report
        cacheConfigs.put("location-service::S4-F8", base.entryTtl(Duration.ofMinutes(15)));  // relationship DTO
        cacheConfigs.put("location-service::S4-F9", base.entryTtl(Duration.ofMinutes(10)));  // combined

        // M2 feature caches
        cacheConfigs.put("location-service::S4-F10", base.entryTtl(Duration.ofMinutes(10))); // analytics dashboard
        cacheConfigs.put("location-service::S4-F12", base.entryTtl(Duration.ofMinutes(5)));  // tracking timeline

        // Default 15 min covers CRUD GET-by-ID entity detail caches
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
}
