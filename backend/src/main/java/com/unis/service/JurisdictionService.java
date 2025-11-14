package com.unis.service;

import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    // GET /api/v1/jurisdictions/{id}
    public Jurisdiction getJurisdiction(UUID jurisdictionId) {
        return jurisdictionRepository.findById(jurisdictionId)
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
    }

    // GET /api/v1/jurisdictions/byName/{name}
    public Optional<Jurisdiction> getByName(String name) {
        return jurisdictionRepository.findByName(name);
    }

    // GET /api/v1/jurisdictions/{id}/tops
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

    // GET /api/v1/jurisdictions/{id}/trending?type=...&genreId=...&limit=... (genreId optional)
    public List<Object[]> getTrendingMediaByJurisdiction(UUID jurisdictionId, String type, UUID genreId, int limit) {
        if ("artist".equals(type)) {
            List<User> artists = userRepository.findTopArtistsByJurisdictionWithHierarchy(jurisdictionId, limit);
            if (genreId != null) {
                artists = artists.stream().filter(a -> a.getGenre().getGenreId().equals(genreId)).toList();
            }
            return artists.stream().map(u -> new Object[] {
                u.getUserId(), u.getUsername(), u.getScore(), u.getPhotoUrl()
            }).toList();
        } else if ("song".equals(type)) {
            List<Song> songs = songRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, limit);
            if (genreId != null) {
                songs = songs.stream().filter(s -> s.getGenre().getGenreId().equals(genreId)).toList();
            }
            return songs.stream().map(s -> new Object[] {
                s.getSongId(), s.getTitle(), s.getScore(), s.getArtworkUrl(), s.getArtist().getUsername()
            }).toList();
        }
        return new ArrayList<>();
    }
}