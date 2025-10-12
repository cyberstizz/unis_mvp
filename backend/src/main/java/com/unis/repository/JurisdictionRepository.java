package com.unis.repository;

import com.unis.entity.Jurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JurisdictionRepository extends JpaRepository<Jurisdiction, UUID> {
    // Find by name (for dropdowns/searches)
    Optional<Jurisdiction> findByName(String name);

    // Find with parent hierarchy (FETCH JOIN for nested jurisdictions)
    @Query("SELECT j FROM Jurisdiction j LEFT JOIN FETCH j.parentJurisdiction WHERE j.jurisdictionId = :id")
    Optional<Jurisdiction> findByIdWithParent(@Param("id") UUID id);

    // Top jurisdictions (e.g., for page 8, order by name or add score if needed)
    @Query("SELECT j FROM Jurisdiction j WHERE j.parentJurisdiction IS NULL ORDER BY j.name")
    List<Jurisdiction> findTopLevelJurisdictions();
}