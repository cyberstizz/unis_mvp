package com.unis.repository;

import com.unis.entity.Playlist;
import com.unis.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {
    List<Playlist> findByUser(User user);
}
