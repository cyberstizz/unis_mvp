package com.unis.repository;

import com.unis.entity.DmcaClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DmcaClaimRepository extends JpaRepository<DmcaClaim, UUID> {

    Page<DmcaClaim> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<DmcaClaim> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(c) FROM DmcaClaim c WHERE c.status = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(c) FROM DmcaClaim c WHERE c.createdAt >= :since")
    long countSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 86400) " +
           "FROM dmca_claims WHERE resolved_at IS NOT NULL AND created_at >= :since",
           nativeQuery = true)
    Double averageResolutionDays(@Param("since") LocalDateTime since);

    @Query("SELECT c FROM DmcaClaim c WHERE c.infringSong.artist.userId = :artistId AND c.status = 'upheld'")
    List<DmcaClaim> findUpheldClaimsAgainstArtist(@Param("artistId") UUID artistId);
}