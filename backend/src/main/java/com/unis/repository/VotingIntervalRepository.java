package com.unis.repository;

import com.unis.entity.VotingInterval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VotingIntervalRepository extends JpaRepository<VotingInterval, UUID> {
    // Find by name (e.g., "Daily" for cron)
    Optional<VotingInterval> findByName(String name);

    // Get all IDs for cron (loop over intervals)
    @Query("SELECT vi.intervalId FROM VotingInterval vi")
    List<UUID> findAllIntervalIds();
}