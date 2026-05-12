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
        
        cacheManager.setCaches(Arrays.asList(
            // Standard caches - 5 minute TTL (moderate change frequency)
            buildCache("songs", 5, TimeUnit.MINUTES),
            buildCache("artists", 5, TimeUnit.MINUTES),
            buildCache("jurisdictions", 5, TimeUnit.MINUTES),
            buildCache("genres", 5, TimeUnit.MINUTES),
            buildCache("userProfiles", 5, TimeUnit.MINUTES),
            buildCache("profileSummaries", 2, TimeUnit.MINUTES),
            buildCache("awards", 10, TimeUnit.MINUTES),           // Historical awards - rarely change
            buildCache("leaderboards", 1, TimeUnit.MINUTES),      // Live rankings - frequent updates
            buildCache("nominees", 1, TimeUnit.MINUTES),          // Voting page nominees - frequent updates
            buildCache("voteCounts", 1, TimeUnit.MINUTES),        // Vote totals displayed on cards
            buildCache("trending", 1, TimeUnit.MINUTES),          // Trending songs - changes constantly

            // Playlist system caches
            buildCache("playlists", 2, TimeUnit.MINUTES),         // Playlist lists/details - matches frontend TTL
            buildCache("blockedSongs", 10, TimeUnit.MINUTES)      // Blocked song IDs - rarely changes
        ));
        
        return cacheManager;
    }
    
    private CaffeineCache buildCache(String name, long duration, TimeUnit unit) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(duration, unit)
            .recordStats()
            .build());
    }
}