package com.unis.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.unis.service.CronMonitorService;
import com.unis.entity.CronExecution;


import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    
    @Autowired
    private CacheManager cacheManager;

    private final CronMonitorService cronMonitorService;

    @Autowired
    public AdminController(CronMonitorService cronMonitorService) {
        this.cronMonitorService = cronMonitorService;
    }
    
    @GetMapping("/cache/stats")
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                Map<String, Object> cacheInfo = new HashMap<>();
                
                // The caches are Caffeine caches wrapped so evictions wait for
                // the database commit (CacheConfig); getNativeCache() reaches
                // through the wrapper. hitRate is the share of reads served
                // from memory since the last restart.
                if (cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache) {
                    com.github.benmanes.caffeine.cache.stats.CacheStats s = nativeCache.stats();
                    cacheInfo.put("size", nativeCache.estimatedSize());
                    cacheInfo.put("hitRate", Math.round(s.hitRate() * 1000) / 10.0);
                    cacheInfo.put("hits", s.hitCount());
                    cacheInfo.put("misses", s.missCount());
                    cacheInfo.put("evictions", s.evictionCount());
                    cacheInfo.put("stats", s.toString());
                } else {
                    cacheInfo.put("type", cache.getClass().getSimpleName());
                }
                
                stats.put(cacheName, cacheInfo);
            }
        });
        
        return stats;
    }
    
    @DeleteMapping("/cache/clear")
    public Map<String, String> clearAllCaches() {
        int count = 0;
        for (String cacheName : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                count++;
            }
        }
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Cleared " + count + " caches");
        response.put("caches", cacheManager.getCacheNames().toString());
        return response;
    }
    
    @DeleteMapping("/cache/clear/{cacheName}")
    public Map<String, String> clearSpecificCache(@PathVariable String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
            return Map.of("message", "Cache '" + cacheName + "' cleared successfully");
        } else {
            return Map.of("error", "Cache '" + cacheName + "' not found");
        }
    }
    
    @GetMapping("/cache/names")
    public Map<String, Object> getCacheNames() {
        return Map.of("caches", cacheManager.getCacheNames());
    }

    @GetMapping("/cron/status")
    public ResponseEntity<?> getCronStatus() {
        return ResponseEntity.ok(cronMonitorService.getStatusSummary());
    }

    @GetMapping("/cron/history/{jobName}")
    public ResponseEntity<?> getCronHistory(@PathVariable String jobName) {
        return ResponseEntity.ok(cronMonitorService.getJobHistory(jobName));
    }
}