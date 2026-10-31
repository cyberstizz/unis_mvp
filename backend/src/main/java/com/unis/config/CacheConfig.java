package com.unis.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The server-side caches (Caffeine, in this JVM's memory).
 *
 * Every cache is wrapped in a TransactionAwareCacheDecorator: when a method
 * that writes to the database also evicts a cache, the eviction happens after
 * the transaction commits. Without it, a request running at the same moment
 * could re-read the old row before the commit and put it straight back in the
 * cache, where it stayed until the entry expired.
 *
 * These caches live in one backend instance. With more than one Railway
 * replica each has its own copy, so an eviction on one replica doesn't reach
 * the others (see the audit report before turning replicas on).
 *
 * Sizes: the number of different keys each cache holds, not bytes.
 * application.yml's old spring.cache block was never used (this bean replaces
 * Spring Boot's cache manager), so it was removed.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        cacheManager.setCaches(List.of(
            // Songs and artists: new releases, top songs, an artist's song list.
            // Nothing evicts these on plays or likes any more (that emptied
            // them several times a minute); they refresh on their own TTL, and
            // uploads, edits and removals still clear them straight away.
            build("songs", 5, TimeUnit.MINUTES, 1_000),
            build("artists", 5, TimeUnit.MINUTES, 2_000),
            // Jurisdictions are reference data plus the "tops" lists.
            build("jurisdictions", 10, TimeUnit.MINUTES, 2_000),
            build("genres", 60, TimeUnit.MINUTES, 100),
            build("userProfiles", 5, TimeUnit.MINUTES, 5_000),
            build("profileSummaries", 2, TimeUnit.MINUTES, 5_000),
            // Award history only changes when the hourly race-close job writes
            // a winner, and that job clears this cache.
            build("awards", 10, TimeUnit.MINUTES, 2_000),
            // Live boards: one minute, many keys (type x race x genre x period
            // x page x day), and a vote clears them.
            build("leaderboards", 1, TimeUnit.MINUTES, 5_000),
            // Finished boards (RaceStandingsService) built from "leaderboards".
            // Separate because a board is computed while holding its cache
            // entry and must not write into the same cache meanwhile.
            build("standings", 1, TimeUnit.MINUTES, 5_000),
            build("nominees", 1, TimeUnit.MINUTES, 1_000),
            build("voteCounts", 1, TimeUnit.MINUTES, 5_000),
            // Trending today: one minute, so "plays today" lags by at most a minute.
            build("trending", 1, TimeUnit.MINUTES, 1_000),

            // Playlist system caches
            build("playlists", 2, TimeUnit.MINUTES, 2_000),
            build("blockedSongs", 10, TimeUnit.MINUTES, 5_000)
        ));

        return cacheManager;
    }

    private static Cache build(String name, long duration, TimeUnit unit, long maxEntries) {
        CaffeineCache cache = new CaffeineCache(name, Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(duration, unit)
            .recordStats()
            .build());
        return new TransactionAwareCacheDecorator(cache);
    }
}
