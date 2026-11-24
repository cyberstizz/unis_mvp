package com.unis.repository;

import com.unis.entity.Release;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository extends JpaRepository<Release, UUID> {

    @Query("SELECT r FROM Release r WHERE r.artistId = :artistId AND r.deletedAt IS NULL ORDER BY r.releasedAt DESC")
    List<Release> findLiveByArtist(@Param("artistId") UUID artistId);

    @Query("SELECT r FROM Release r WHERE r.releaseId = :id AND r.deletedAt IS NULL")
    Optional<Release> findLive(@Param("id") UUID id);
}
