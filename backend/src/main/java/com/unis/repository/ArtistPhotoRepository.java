package com.unis.repository;

import com.unis.entity.ArtistPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArtistPhotoRepository extends JpaRepository<ArtistPhoto, UUID> {

    List<ArtistPhoto> findByArtistIdOrderByPositionAscCreatedAtAsc(UUID artistId);

    long countByArtistId(UUID artistId);
}