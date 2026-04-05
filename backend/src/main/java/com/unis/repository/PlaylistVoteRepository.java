package com.unis.repository;

import com.unis.entity.PlaylistVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PlaylistVoteRepository extends JpaRepository<PlaylistVote, UUID> {

    Optional<PlaylistVote> findByPlaylistItem_PlaylistItemIdAndUser_UserId(UUID itemId, UUID userId);

    boolean existsByPlaylistItem_PlaylistItemIdAndUser_UserId(UUID itemId, UUID userId);

    void deleteByPlaylistItem_PlaylistItemIdAndUser_UserId(UUID itemId, UUID userId);

    @Query("SELECT COUNT(pv) FROM PlaylistVote pv WHERE pv.playlistItem.playlistItemId = :itemId " +
           "AND pv.voteType = 'up'")
    int countUpvotes(@Param("itemId") UUID itemId);

    @Query("SELECT COUNT(pv) FROM PlaylistVote pv WHERE pv.playlistItem.playlistItemId = :itemId " +
           "AND pv.voteType = 'down'")
    int countDownvotes(@Param("itemId") UUID itemId);
}