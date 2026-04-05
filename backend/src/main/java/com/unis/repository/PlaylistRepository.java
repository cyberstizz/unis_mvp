package com.unis.repository;

import com.unis.entity.Playlist;
import com.unis.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    // --- Legacy (kept for backward compat) ---
    List<Playlist> findByUser(User user);

    // --- Personal playlists for authenticated user ---
    @Query("SELECT p FROM Playlist p WHERE p.user.userId = :userId AND p.deletedAt IS NULL ORDER BY p.updatedAt DESC")
    List<Playlist> findByOwner(@Param("userId") UUID userId);

    // --- Single playlist ignoring soft delete filter (for admin recovery) ---
    @Query("SELECT p FROM Playlist p WHERE p.playlistId = :id")
    Optional<Playlist> findByIdIncludingDeleted(@Param("id") UUID id);

    // --- Discovery: public playlists by jurisdiction ---
    @Query("SELECT p FROM Playlist p WHERE p.visibility = 'public' AND p.deletedAt IS NULL " +
           "AND p.jurisdiction.jurisdictionId = :jId ORDER BY p.followerCount DESC")
    List<Playlist> findPublicByJurisdiction(@Param("jId") UUID jurisdictionId);

    // --- Discovery: all public playlists (global browse) ---
    @Query("SELECT p FROM Playlist p WHERE p.visibility = 'public' AND p.deletedAt IS NULL " +
           "ORDER BY p.followerCount DESC")
    List<Playlist> findAllPublic();

    // --- Community playlists for a jurisdiction ---
    @Query("SELECT p FROM Playlist p WHERE p.type = 'community' AND p.visibility = 'public' " +
           "AND p.deletedAt IS NULL AND p.jurisdiction.jurisdictionId = :jId " +
           "ORDER BY p.followerCount DESC")
    List<Playlist> findCommunityByJurisdiction(@Param("jId") UUID jurisdictionId);

    // --- Official playlists ---
    @Query("SELECT p FROM Playlist p WHERE p.type = 'official' AND p.deletedAt IS NULL " +
           "ORDER BY p.updatedAt DESC")
    List<Playlist> findOfficialPlaylists();

    // --- Search public playlists by name ---
    @Query("SELECT p FROM Playlist p WHERE p.visibility = 'public' AND p.deletedAt IS NULL " +
           "AND LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY p.followerCount DESC")
    List<Playlist> searchPublicPlaylists(@Param("q") String query);

    // --- Soft delete ---
    @Modifying
    @Query("UPDATE Playlist p SET p.deletedAt = :now WHERE p.playlistId = :id")
    void softDelete(@Param("id") UUID playlistId, @Param("now") LocalDateTime now);

    // --- Denormalized counter updates (atomic, no race conditions) ---
    @Modifying
    @Query("UPDATE Playlist p SET p.followerCount = p.followerCount + :delta WHERE p.playlistId = :id")
    void updateFollowerCount(@Param("id") UUID playlistId, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Playlist p SET p.songCount = p.songCount + :delta WHERE p.playlistId = :id")
    void updateSongCount(@Param("id") UUID playlistId, @Param("delta") int delta);
}