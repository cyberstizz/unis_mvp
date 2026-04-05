package com.unis.repository;

import com.unis.entity.BlockedSong;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BlockedSongRepository extends JpaRepository<BlockedSong, UUID> {

    boolean existsByUser_UserIdAndSong_SongId(UUID userId, UUID songId);

    void deleteByUser_UserIdAndSong_SongId(UUID userId, UUID songId);

    List<BlockedSong> findByUser_UserId(UUID userId);

    @Query("SELECT bs.song.songId FROM BlockedSong bs WHERE bs.user.userId = :userId")
    List<UUID> findBlockedSongIdsByUserId(@Param("userId") UUID userId);
}