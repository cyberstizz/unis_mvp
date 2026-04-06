package com.unis.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        
        // Configure each cache with its own TTL based on data volatility
        cacheManager.setCaches(Arrays.asList(
            // Standard caches - 5 minute TTL (moderate change frequency)
            buildCache("songs", 5, TimeUnit.MINUTES),
            buildCache("artists", 5, TimeUnit.MINUTES),
            buildCache("jurisdictions", 5, TimeUnit.MINUTES),
            buildCache("genres", 5, TimeUnit.MINUTES),
            buildCache("userProfiles", 5, TimeUnit.MINUTES),
            buildCache("awards", 10, TimeUnit.MINUTES),           // Historical awards - rarely change
            buildCache("leaderboards", 1, TimeUnit.MINUTES),      // Live rankings - frequent updates + immediate eviction
            buildCache("nominees", 1, TimeUnit.MINUTES),          // Voting page nominees - frequent updates + immediate eviction
            buildCache("voteCounts", 1, TimeUnit.MINUTES),        // Vote totals displayed on cards - frequent updates + immediate eviction
            buildCache("trending", 1, TimeUnit.MINUTES)           // Trending songs - changes constantly
        ));
        
        return cacheManager;
    }
    
    /**
     * @param name Cache name (must match @Cacheable value)
     * @param duration TTL duration
     * @param unit TTL time unit
     * @return Configured CaffeineCache
     */
    private CaffeineCache buildCache(String name, long duration, TimeUnit unit) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .maximumSize(1000)                          // Max 1000 entries per cache
            .expireAfterWrite(duration, unit)           // TTL after write
            .recordStats()                              // Enable monitoring stats
            .build());
    }
}