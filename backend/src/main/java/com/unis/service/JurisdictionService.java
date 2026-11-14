package com.unis.service;

import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import com.unis.util.CacheReady;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// The cached methods below return entities. Each one loads every link its JSON
// shows (CacheReady) inside a read-only transaction before the result is
// cached, so a cached copy never has to reach back into the database session
// of the request that first loaded it.
@Service
public class JurisdictionService {
    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    // CACHED: Jurisdiction lookup by ID (1 hour TTL - rarely changes)
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "#jurisdictionId")
    public Jurisdiction getJurisdiction(UUID jurisdictionId) {
        return CacheReady.jurisdiction(jurisdictionRepository.findById(jurisdictionId)
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found")));
    }

    // CACHED: Jurisdiction lookup by name (1 hour TTL - rarely changes)
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'name-' + #name")
    public List<Jurisdiction> getByName(String name) {
        return CacheReady.jurisdictions(jurisdictionRepository.findAllByNameIgnoreCase(name));
    }

    // CACHED: Tops for a jurisdiction (5 min TTL - updates with song plays/votes)
    // This is expensive with recursive hierarchy queries
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'tops-' + #jurisdictionId")
    public Map<String, Object> getJurisdictionTops(UUID jurisdictionId) {
        List<User> topArtists = CacheReady.users(
            userRepository.findTopArtistsByJurisdictionWithHierarchy(jurisdictionId, 30));
        List<Song> topSongs = CacheReady.songs(
            songRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, 30));

        Map<String, Object> tops = new HashMap<>();
        tops.put("topArtists", topArtists);
        tops.put("topSongs", topSongs);

        // Slice for #1 highlights
        tops.put("topArtist", topArtists.isEmpty() ? null : topArtists.get(0));
        tops.put("topSong", topSongs.isEmpty() ? null : topSongs.get(0));

        return tops;
    }

    // CACHED: Trending media by type and genre (5 min TTL)
    // Cache key includes all parameters since results differ
    @Cacheable(value = "jurisdictions", 
               key = "'trending-' + #jurisdictionId + '-' + #type + '-' + #genreId + '-' + #limit")
    public List<Object[]> getTrendingMediaByJurisdiction(UUID jurisdictionId, String type, UUID genreId, int limit) {
        if ("artist".equals(type)) {
            List<User> artists = userRepository.findTopArtistsByJurisdictionWithHierarchy(jurisdictionId, limit);
            if (genreId != null) {
                artists = artists.stream().filter(a -> a.getGenre() != null && a.getGenre().getGenreId().equals(genreId)).toList();
            }
            return artists.stream().map(u -> new Object[] {
                u.getUserId(), u.getUsername(), u.getScore(), u.getPhotoUrl()
            }).toList();
        } else if ("song".equals(type)) {
            List<Song> songs = songRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, limit);
            if (genreId != null) {
                songs = songs.stream().filter(s -> s.getGenre() != null && s.getGenre().getGenreId().equals(genreId)).toList();
            }
            return songs.stream().map(s -> new Object[] {
                s.getSongId(), s.getTitle(), s.getScore(), s.getArtworkUrl(), s.getArtist().getUsername()
            }).toList();
        }
        return new ArrayList<>();
    }

    // =====================================================================
    // NEW METHODS FOR HIERARCHY NAVIGATION (FindPage map drill-down)
    // =====================================================================

    /**
     * Get all direct children of a jurisdiction
     * Used for map drill-down: clicking a jurisdiction shows its children
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'children-' + #parentJurisdictionId")
    public List<Jurisdiction> getChildren(UUID parentJurisdictionId) {
        return CacheReady.jurisdictions(jurisdictionRepository.findByParentJurisdictionId(parentJurisdictionId));
    }

    /**
     * Get root jurisdictions (no parent - typically just "Unis")
     * For the very top level of the hierarchy
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'roots'")
    public List<Jurisdiction> getRootJurisdictions() {
        return CacheReady.jurisdictions(jurisdictionRepository.findTopLevelJurisdictions());
    }

    /**
     * Check if a jurisdiction has children (for UI - show drill-down indicator)
     */
    @Cacheable(value = "jurisdictions", key = "'hasChildren-' + #jurisdictionId")
    public boolean hasChildren(UUID jurisdictionId) {
        return jurisdictionRepository.hasChildren(jurisdictionId);
    }

    /**
     * Get the parent chain for breadcrumb navigation
     * Returns list from root down to the specified jurisdiction
     */
    @Cacheable(value = "jurisdictions", key = "'parentChain-' + #jurisdictionId")
    public List<Map<String, Object>> getParentChain(UUID jurisdictionId) {
        List<Object[]> rawResults = jurisdictionRepository.findParentChain(jurisdictionId);
        List<Map<String, Object>> chain = new ArrayList<>();
        
        for (Object[] row : rawResults) {
            Map<String, Object> item = new HashMap<>();
            item.put("jurisdictionId", row[0]);
            item.put("name", row[1]);
            item.put("parentJurisdictionId", row[2]);
            item.put("polygon", row[3]);
            item.put("bio", row[4]);
            item.put("symbolUrl", row[5]);
            chain.add(item);
        }
        
        return chain;
    }

    /**
     * Get children with additional metadata for the map
     * Includes hasChildren flag and active status.
     *
     * isActive is the jurisdiction's own voting_enabled flag: the one switch
     * that says a neighborhood is open. Open one with
     *   UPDATE jurisdictions SET voting_enabled = true WHERE name = '...';
     * (see sql/V2026_11a__jurisdiction_open_flag.sql). Cached up to 10 minutes.
     */
    @Cacheable(value = "jurisdictions", key = "'childrenWithMeta-' + #parentJurisdictionId")
    public List<Map<String, Object>> getChildrenWithMetadata(UUID parentJurisdictionId) {
        List<Jurisdiction> children = jurisdictionRepository.findByParentJurisdictionId(parentJurisdictionId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Jurisdiction child : children) {
            Map<String, Object> item = new HashMap<>();
            item.put("jurisdictionId", child.getJurisdictionId());
            item.put("name", child.getName());
            item.put("polygon", child.getPolygon());
            item.put("bio", child.getBio());
            item.put("symbolUrl", child.getSymbolUrl());
            item.put("hasChildren", jurisdictionRepository.hasChildren(child.getJurisdictionId()));
            item.put("isActive", Boolean.TRUE.equals(child.getVotingEnabled()));
            
            // Get parent info
            if (child.getParentJurisdiction() != null) {
                item.put("parentJurisdictionId", child.getParentJurisdiction().getJurisdictionId());
                item.put("parentName", child.getParentJurisdiction().getName());
            }
            
            result.add(item);
        }
        
        return result;
    }

    /**
     * Every open jurisdiction (voting_enabled = true), each with its chain of
     * places from the root down to itself. The Find page reads this instead of
     * hardcoding which states and neighborhoods are live: a state is live when
     * it appears in some open jurisdiction's chain.
     *
     * [{ jurisdictionId, name, chain: [{ jurisdictionId, name }, ... self] }]
     *
     * Cached up to 10 minutes, so opening a neighborhood in SQL shows on the
     * map within 10 minutes (or right away after a restart).
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'open'")
    public List<Map<String, Object>> getOpenJurisdictions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Jurisdiction j : jurisdictionRepository.findVotingEnabledJurisdictions()) {
            List<Map<String, Object>> chain = new ArrayList<>();
            for (Object[] row : jurisdictionRepository.findParentChain(j.getJurisdictionId())) {
                Map<String, Object> link = new HashMap<>();
                link.put("jurisdictionId", row[0]);
                link.put("name", row[1]);
                chain.add(link);
            }
            Map<String, Object> item = new HashMap<>();
            item.put("jurisdictionId", j.getJurisdictionId());
            item.put("name", j.getName());
            item.put("chain", chain);
            result.add(item);
        }
        return result;
    }

    /**
     * Find the most specific jurisdiction containing a geographic point
     * Used for user signup to auto-assign jurisdiction based on location
     * Returns the deepest (most specific) jurisdiction that contains the point
     */
    public Optional<Jurisdiction> findJurisdictionByLocation(double lat, double lng) {
        List<Object[]> results = jurisdictionRepository.findJurisdictionsContainingPoint(lat, lng);
        
        if (results.isEmpty()) {
            return Optional.empty();
        }
        
        // Find the most specific (deepest) jurisdiction
        // The one with the most parents in its chain is the most specific
        UUID mostSpecificId = null;
        int maxDepth = 0;
        
        for (Object[] row : results) {
            UUID jurisdictionId = (UUID) row[0];
            List<Map<String, Object>> chain = getParentChain(jurisdictionId);
            if (chain.size() > maxDepth) {
                maxDepth = chain.size();
                mostSpecificId = jurisdictionId;
            }
        }
        
        if (mostSpecificId != null) {
            return jurisdictionRepository.findById(mostSpecificId);
        }
        
        return Optional.empty();
    }

    /**
     * Get all states (Tier 2) - direct children of Unis
     * Used for the US map view
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "jurisdictions", key = "'states'")
    public List<Jurisdiction> getAllStates() {
        // First find Unis
        List<Jurisdiction> unisList = jurisdictionRepository.findAllByNameIgnoreCase("Unis");
        if (unisList.isEmpty()) {
            return List.of();
        }

        Jurisdiction unis = unisList.get(0);
        
        // Return children of Unis (all states)
        return CacheReady.jurisdictions(jurisdictionRepository.findByParentJurisdictionId(unis.getJurisdictionId()));
    }
}