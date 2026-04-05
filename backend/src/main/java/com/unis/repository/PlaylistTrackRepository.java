package com.unis.repository;

import com.unis.entity.PlaylistTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaylistTrackRepository extends JpaRepository<PlaylistTrack, UUID> {

    // --- Legacy ---
    List<PlaylistTrack> findByPlaylistPlaylistIdOrderByPosition(UUID playlistId);

    // --- Active tracks only (excludes removed/pending) ---
    @Query("SELECT pt FROM PlaylistTrack pt WHERE pt.playlist.playlistId = :playlistId " +
           "AND pt.status = 'active' ORDER BY pt.position ASC")
    List<PlaylistTrack> findActiveByPlaylist(@Param("playlistId") UUID playlistId);

    // --- Pending suggestions (community playlists) ---
    @Query("SELECT pt FROM PlaylistTrack pt WHERE pt.playlist.playlistId = :playlistId " +
           "AND pt.status = 'pending' ORDER BY pt.addedAt ASC")
    List<PlaylistTrack> findPendingByPlaylist(@Param("playlistId") UUID playlistId);

    // --- Check if song already exists in playlist (any status) ---
    boolean existsByPlaylist_PlaylistIdAndSong_SongId(UUID playlistId, UUID songId);

    // --- Count active tracks ---
    @Query("SELECT COUNT(pt) FROM PlaylistTrack pt WHERE pt.playlist.playlistId = :playlistId " +
           "AND pt.status = 'active'")
    int countActiveByPlaylist(@Param("playlistId") UUID playlistId);
}