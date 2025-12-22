package com.unis.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
            "songs",           // Individual song metadata
            "artists",         // Artist profiles
            "jurisdictions",   // Jurisdiction hierarchy
            "genres",          // Genre list
            "trending",        // Trending songs (short TTL)
            "userProfiles"     // User profiles
        );
        
        cacheManager.setCaffeine(caffeine());
        return cacheManager;
    }

    private Caffeine<Object, Object> caffeine() {
        return Caffeine.newBuilder()
            .maximumSize(1000)           // Max 1000 entries per cache
            .expireAfterWrite(5, TimeUnit.MINUTES)  // Default 5 min TTL
            .recordStats();              // Enable stats for monitoring
    }
}