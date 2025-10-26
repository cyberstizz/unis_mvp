package com.unis.service;

import com.unis.entity.Jurisdiction;
import com.unis.entity.User;
import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.service.MediaService;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class JurisdictionService {
    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private SongPlayRepository songPlayRepository;

    @Autowired
    private VideoPlayRepository videoPlayRepository;

    @Autowired
    private MediaService mediaService;

    // Get jurisdiction details with bio (page 8)
    public Jurisdiction getJurisdiction(UUID jurisdictionId) {
        return jurisdictionRepository.findByIdWithParent(jurisdictionId)
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
    }

    // Top 30 artists by score/votes (page 8)
    public List<User> getTopArtistsByJurisdiction(UUID jurisdictionId, int limit) {
        return userRepository.findTopArtistsByJurisdiction(jurisdictionId, limit);
    }

    // Trending 30 songs/videos by plays today (page 8)
    public List<Object[]> getTrendingMediaByJurisdiction(UUID jurisdictionId, String type, int limit) {
        if ("song".equals(type)) {
            return songPlayRepository.findTrendingByJurisdiction(jurisdictionId, limit);
        } else {
            return videoPlayRepository.findTrendingByJurisdiction(jurisdictionId, limit);
        }
    }

    // Top 30 for jurisdiction (combined artists/songs with ranks)
    public Map<String, Object> getJurisdictionTops(UUID jurisdictionId) {
        List<User> topArtists = getTopArtistsByJurisdiction(jurisdictionId, 30);
        List<Song> topSongs = mediaService.getTopSongsByJurisdiction(jurisdictionId, 30);  // Uses recursive
        List<Video> topVideos = mediaService.getTopVideosByJurisdiction(jurisdictionId, 30);  // Uses recursive
        Map<String, Object> tops = new HashMap<>();
            tops.put("topArtists", topArtists);
            tops.put("topSongs", topSongs);
            tops.put("topVideos", topVideos);
        return tops;
}
}