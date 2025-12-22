package com.unis.service;

import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JurisdictionService {
    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    // CACHED: Jurisdiction lookup by ID (1 hour TTL - rarely changes)
    @Cacheable(value = "jurisdictions", key = "#jurisdictionId")
    public Jurisdiction getJurisdiction(UUID jurisdictionId) {
        return jurisdictionRepository.findById(jurisdictionId)
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
    }

    // CACHED: Jurisdiction lookup by name (1 hour TTL - rarely changes)
    @Cacheable(value = "jurisdictions", key = "'name-' + #name")
    public Optional<Jurisdiction> getByName(String name) {
        return jurisdictionRepository.findByName(name);
    }

    // CACHED: Tops for a jurisdiction (5 min TTL - updates with song plays/votes)
    // This is expensive with recursive hierarchy queries
    @Cacheable(value = "jurisdictions", key = "'tops-' + #jurisdictionId")
    public Map<String, Object> getJurisdictionTops(UUID jurisdictionId) {
        List<User> topArtists = userRepository.findTopArtistsByJurisdictionWithHierarchy(jurisdictionId, 30);
        List<Song> topSongs = songRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, 30);

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
}