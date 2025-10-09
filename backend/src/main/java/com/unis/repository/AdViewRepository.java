package com.unis.repository;

import com.unis.entity.AdView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

@Repository
public interface AdViewRepository extends JpaRepository<AdView, UUID> {
    @Query("SELECT SUM(a.revenueShare) FROM AdView a WHERE a.supportedArtist.userId = :artistId AND DATE(a.viewedAt) = :date")
    BigDecimal sumEarningsByDay(@Param("artistId") UUID artistId, @Param("date") LocalDate date);

    // For earnings aggregate (last 30 days)
    @Query("SELECT DATE(a.viewedAt) as day, SUM(a.revenueShare) as total FROM AdView a WHERE a.supportedArtist.userId = :artistId AND a.viewedAt >= :startDate GROUP BY day ORDER BY day")
    List<Object[]> getEarningsLastDays(@Param("artistId") UUID artistId, @Param("startDate") LocalDateTime startDate);
}