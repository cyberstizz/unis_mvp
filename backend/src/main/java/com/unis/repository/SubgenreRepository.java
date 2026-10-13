package com.unis.repository;

import com.unis.entity.Subgenre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubgenreRepository extends JpaRepository<Subgenre, UUID> {

    /** What the upload form offers: active subgenres, in display order. */
    List<Subgenre> findByActiveTrueOrderByGenreIdAscSortOrderAsc();

    Optional<Subgenre> findBySlug(String slug);
}
