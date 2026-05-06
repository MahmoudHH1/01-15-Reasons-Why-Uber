package com.team01.uber.location.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoDatabaseFactorySupport;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class MongoConfig {

    @Value("${spring.data.mongodb.uri:mongodb://root:rootpass@mongo:27017/ubermongo?authSource=admin}")
    private String mongoUri;

    @Bean
    @Primary
    public MongoClient mongoClient() {
        ConnectionString cs = new ConnectionString(mongoUri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(cs)
                .applyToSocketSettings(b -> b.connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
        System.out.println("[MongoConfig] Connecting to: " + cs.getHosts() + " db=" + cs.getDatabase() + " user=" + cs.getUsername());
        return MongoClients.create(settings);
    }

    @Bean
    @Primary
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        ConnectionString cs = new ConnectionString(mongoUri);
        String db = cs.getDatabase() != null ? cs.getDatabase() : "ubermongo";
        return new SimpleMongoClientDatabaseFactory(mongoClient, db);
    }

    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory factory) {
        return new MongoTemplate(factory);
    }
}