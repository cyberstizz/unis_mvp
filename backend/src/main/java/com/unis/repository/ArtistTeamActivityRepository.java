package com.unis.repository;

import com.unis.entity.ArtistTeamActivity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArtistTeamActivityRepository extends JpaRepository<ArtistTeamActivity, UUID> {

    List<ArtistTeamActivity> findByArtistIdOrderByCreatedAtDesc(UUID artistId, Pageable page);
}
