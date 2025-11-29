package com.unis.service;

import com.unis.entity.Playlist;
import com.unis.entity.PlaylistTrack;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.PlaylistRepository;
import com.unis.repository.PlaylistTrackRepository;
import com.unis.repository.SongRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final SongRepository songRepository;

    public PlaylistService(PlaylistRepository playlistRepository,
                           PlaylistTrackRepository playlistTrackRepository,
                           SongRepository songRepository) {
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.songRepository = songRepository;
    }

    public List<Playlist> getPlaylistsForUser(User user) {
        return playlistRepository.findByUser(user);
    }

    @Transactional
    public Playlist createPlaylist(User user, String name) {
        Playlist p = Playlist.builder()
                .name(name)
                .user(user)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();
        return playlistRepository.save(p);
    }

    @Transactional
    public Playlist addSongToPlaylist(UUID playlistId, UUID songId, User user) {
        Playlist pl = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
        
        // Check ownership
        if (!pl.getUser().getUserId().equals(user.getUserId())) {
            throw new RuntimeException("Unauthorized");
        }

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found"));

        // Check if song already exists in playlist
        boolean exists = pl.getItems().stream()
                .anyMatch(pt -> pt.getSong().getSongId().equals(songId));
        
        if (exists) {
            throw new RuntimeException("Song already in playlist");
        }

        // Compute next position
        int pos = pl.getItems().size();
        PlaylistTrack pt = PlaylistTrack.builder()
                .playlist(pl)
                .song(song)
                .position(pos)
                .addedAt(LocalDateTime.now())
                .build();

        pl.getItems().add(pt);
        pl.setUpdatedAt(LocalDateTime.now());
        return playlistRepository.save(pl);
    }

    @Transactional
    public Playlist removeSongFromPlaylist(UUID playlistId, UUID playlistItemId, User user) {
        Playlist pl = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
        
        // Check ownership
        if (!pl.getUser().getUserId().equals(user.getUserId())) {
            throw new RuntimeException("Unauthorized");
        }

        pl.getItems().removeIf(t -> t.getPlaylistItemId().equals(playlistItemId));

        // Reindex positions
        for (int i = 0; i < pl.getItems().size(); i++) {
            pl.getItems().get(i).setPosition(i);
        }

        pl.setUpdatedAt(LocalDateTime.now());
        return playlistRepository.save(pl);
    }

    @Transactional
    public Playlist reorderPlaylist(UUID playlistId, List<UUID> orderedTrackIds, User user) {
        Playlist pl = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
        
        // Check ownership
        if (!pl.getUser().getUserId().equals(user.getUserId())) {
            throw new RuntimeException("Unauthorized");
        }

        // Map playlistItemId -> PlaylistTrack
        Map<UUID, PlaylistTrack> map = new HashMap<>();
        for (PlaylistTrack pt : pl.getItems()) {
            map.put(pt.getPlaylistItemId(), pt);
        }

        List<PlaylistTrack> newOrder = new ArrayList<>();
        int pos = 0;
        for (UUID trackId : orderedTrackIds) {
            PlaylistTrack pt = map.get(trackId);
            if (pt != null) {
                pt.setPosition(pos++);
                newOrder.add(pt);
            }
        }
        
        pl.getItems().clear();
        pl.getItems().addAll(newOrder);
        pl.setUpdatedAt(LocalDateTime.now());
        return playlistRepository.save(pl);
    }

    public Optional<Playlist> getPlaylist(UUID id, User user) {
        Optional<Playlist> playlist = playlistRepository.findById(id);
        
        // Check ownership
        if (playlist.isPresent() && !playlist.get().getUser().getUserId().equals(user.getUserId())) {
            return Optional.empty();
        }
        
        return playlist;
    }

    @Transactional
    public void deletePlaylist(UUID playlistId, User user) {
        Playlist pl = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
        
        // Check ownership
        if (!pl.getUser().getUserId().equals(user.getUserId())) {
            throw new RuntimeException("Unauthorized");
        }
        
        playlistRepository.delete(pl);
    }

    @Transactional
    public Playlist updatePlaylistName(UUID playlistId, String name, User user) {
        Playlist pl = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));
        
        // Check ownership
        if (!pl.getUser().getUserId().equals(user.getUserId())) {
            throw new RuntimeException("Unauthorized");
        }
        
        pl.setName(name);
        pl.setUpdatedAt(LocalDateTime.now());
        return playlistRepository.save(pl);
    }
}