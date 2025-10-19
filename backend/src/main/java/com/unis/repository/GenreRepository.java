package com.unis.repository;

import com.unis.entity.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GenreRepository extends JpaRepository<Genre, UUID> {
    // Find by name (for vote/award lookups)
    Optional<Genre> findByName(String name);

    // Get all IDs for cron (loop over genres)
    @Query("SELECT g.genreId FROM Genre g")
    List<UUID> findAllGenreIds();
}