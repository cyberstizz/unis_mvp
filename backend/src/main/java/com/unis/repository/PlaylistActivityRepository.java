package com.unis.repository;

import com.unis.entity.PlaylistActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaylistActivityRepository extends JpaRepository<PlaylistActivity, UUID> {

    List<PlaylistActivity> findByPlaylist_PlaylistIdOrderByCreatedAtDesc(UUID playlistId);

    Page<PlaylistActivity> findByPlaylist_PlaylistId(UUID playlistId, Pageable pageable);
}