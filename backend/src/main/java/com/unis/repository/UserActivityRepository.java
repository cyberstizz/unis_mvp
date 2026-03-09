package com.unis.repository;

import com.unis.entity.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {

    @Query(value = "SELECT DATE(created_at) as day, COUNT(DISTINCT user_id) as count " +
                   "FROM user_activity WHERE created_at >= :startDate AND created_at < :endDate " +
                   "GROUP BY DATE(created_at) ORDER BY day", nativeQuery = true)
    List<Object[]> getDailyActiveUsers(@Param("startDate") LocalDateTime startDate,
                                        @Param("endDate") LocalDateTime endDate);

    @Query(value = "SELECT TO_CHAR(created_at, 'YYYY-MM') as month, COUNT(DISTINCT user_id) as count " +
                   "FROM user_activity WHERE created_at >= :since " +
                   "GROUP BY TO_CHAR(created_at, 'YYYY-MM') ORDER BY month", nativeQuery = true)
    List<Object[]> getMonthlyActiveUsers(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COUNT(DISTINCT user_id) FROM user_activity " +
                   "WHERE created_at >= :startOfDay AND created_at < :endOfDay", nativeQuery = true)
    long countActiveUsersForDay(@Param("startOfDay") LocalDateTime startOfDay,
                                 @Param("endOfDay") LocalDateTime endOfDay);
}