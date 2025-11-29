package com.unis.repository;

import com.unis.entity.PlaylistTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

public interface PlaylistTrackRepository extends JpaRepository<PlaylistTrack, UUID> {
    List<PlaylistTrack> findByPlaylistPlaylistIdOrderByPosition(UUID playlistId);
}
