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
    List<Jurisdiction> findAllByNameIgnoreCase(String name);

    // Find with parent hierarchy (FETCH JOIN for nested jurisdictions)
    @Query("SELECT j FROM Jurisdiction j LEFT JOIN FETCH j.parentJurisdiction WHERE j.jurisdictionId = :id")
    Optional<Jurisdiction> findByIdWithParent(@Param("id") UUID id);

    // Top jurisdictions (e.g., for page 8, order by name or add score if needed)
    @Query("SELECT j FROM Jurisdiction j WHERE j.parentJurisdiction IS NULL ORDER BY j.name")
    List<Jurisdiction> findTopLevelJurisdictions();

    // Get all IDs for cron (loop over jurisdictions)
    @Query("SELECT j.jurisdictionId FROM Jurisdiction j")
    List<UUID> findAllJurisdictionIds();

    // =====================================================================
    // NEW METHODS FOR HIERARCHY NAVIGATION (FindPage map drill-down)
    // =====================================================================

    /**
     * Find all direct children of a jurisdiction
     * Used for map drill-down navigation
     */
    @Query("SELECT j FROM Jurisdiction j WHERE j.parentJurisdiction.jurisdictionId = :parentId ORDER BY j.name")
    List<Jurisdiction> findByParentJurisdictionId(@Param("parentId") UUID parentId);

    /**
     * Find all jurisdictions at a specific tier level
     * Tier is determined by counting parents up to root
     * This uses a native query for efficiency
     */
    @Query(value = """
        WITH RECURSIVE jurisdiction_tree AS (
            SELECT jurisdiction_id, name, parent_jurisdiction_id, polygon, bio, symbol_url, 1 as tier
            FROM jurisdictions
            WHERE parent_jurisdiction_id IS NULL
            
            UNION ALL
            
            SELECT j.jurisdiction_id, j.name, j.parent_jurisdiction_id, j.polygon, j.bio, j.symbol_url, jt.tier + 1
            FROM jurisdictions j
            INNER JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id
        )
        SELECT jurisdiction_id, name, parent_jurisdiction_id, polygon, bio, symbol_url
        FROM jurisdiction_tree
        WHERE tier = :tier
        ORDER BY name
        """, nativeQuery = true)
    List<Object[]> findByTier(@Param("tier") int tier);

    /**
     * Get the full parent chain for a jurisdiction (for breadcrumb navigation)
     * Returns from the given jurisdiction up to the root
     */
    @Query(value = """
        WITH RECURSIVE parent_chain AS (
            SELECT jurisdiction_id, name, parent_jurisdiction_id, polygon, bio, symbol_url, 1 as depth
            FROM jurisdictions
            WHERE jurisdiction_id = :jurisdictionId
            
            UNION ALL
            
            SELECT j.jurisdiction_id, j.name, j.parent_jurisdiction_id, j.polygon, j.bio, j.symbol_url, pc.depth + 1
            FROM jurisdictions j
            INNER JOIN parent_chain pc ON j.jurisdiction_id = pc.parent_jurisdiction_id
        )
        SELECT jurisdiction_id, name, parent_jurisdiction_id, polygon, bio, symbol_url
        FROM parent_chain
        ORDER BY depth DESC
        """, nativeQuery = true)
    List<Object[]> findParentChain(@Param("jurisdictionId") UUID jurisdictionId);

    /**
     * Check if a jurisdiction has any children
     */
    @Query("SELECT COUNT(j) > 0 FROM Jurisdiction j WHERE j.parentJurisdiction.jurisdictionId = :parentId")
    boolean hasChildren(@Param("parentId") UUID parentId);

    /**
     * Find all jurisdictions that contain a specific point (lat/lng)
     * This will be used for user location assignment
     * Note: Requires polygon data to be stored as valid GeoJSON
     */
    @Query(value = """
        SELECT j.jurisdiction_id, j.name, j.parent_jurisdiction_id, j.polygon, j.bio, j.symbol_url
        FROM jurisdictions j
        WHERE j.polygon IS NOT NULL
        AND ST_Contains(
            ST_SetSRID(ST_GeomFromGeoJSON(j.polygon), 4326),
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
        )
        ORDER BY j.name
        """, nativeQuery = true)
    List<Object[]> findJurisdictionsContainingPoint(@Param("lat") double lat, @Param("lng") double lng);
}