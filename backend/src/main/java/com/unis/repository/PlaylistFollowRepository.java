package com.unis.repository;

import com.unis.entity.PlaylistFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaylistFollowRepository extends JpaRepository<PlaylistFollow, UUID> {

    boolean existsByPlaylist_PlaylistIdAndUser_UserId(UUID playlistId, UUID userId);

    void deleteByPlaylist_PlaylistIdAndUser_UserId(UUID playlistId, UUID userId);

    @Query("SELECT pf.playlist.playlistId FROM PlaylistFollow pf WHERE pf.user.userId = :userId")
    List<UUID> findFollowedPlaylistIds(@Param("userId") UUID userId);

    @Query("SELECT pf.playlist FROM PlaylistFollow pf WHERE pf.user.userId = :userId " +
           "AND pf.playlist.deletedAt IS NULL ORDER BY pf.followedAt DESC")
    List<com.unis.entity.Playlist> findFollowedPlaylists(@Param("userId") UUID userId);

    int countByPlaylist_PlaylistId(UUID playlistId);
}