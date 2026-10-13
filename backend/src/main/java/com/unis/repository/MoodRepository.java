package com.unis.repository;

import com.unis.entity.Mood;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MoodRepository extends JpaRepository<Mood, UUID> {

    /** What the upload form offers: active moods, in display order. */
    List<Mood> findByActiveTrueOrderBySortOrderAsc();

    Optional<Mood> findBySlug(String slug);
}
