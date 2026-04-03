package com.unis.repository;

import com.unis.entity.CronExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CronExecutionRepository extends JpaRepository<CronExecution, UUID> {

    // Get the most recent execution for a specific job
    Optional<CronExecution> findTopByJobNameOrderByStartedAtDesc(String jobName);

    // Get the most recent execution for each job type (used by dashboard)
    @Query(value = """
        SELECT DISTINCT ON (job_name) *
        FROM cron_executions
        ORDER BY job_name, started_at DESC
        """, nativeQuery = true)
    List<CronExecution> findLatestByEachJob();

    // Get last N executions for a specific job (for history view)
    List<CronExecution> findTop20ByJobNameOrderByStartedAtDesc(String jobName);

    // Get all executions in the last 24 hours
    @Query("SELECT c FROM CronExecution c WHERE c.startedAt >= :since ORDER BY c.startedAt DESC")
    List<CronExecution> findRecentExecutions(java.time.LocalDateTime since);

    // Count failures in last 7 days (for alerting)
    @Query("SELECT COUNT(c) FROM CronExecution c WHERE c.status = 'FAILED' AND c.startedAt >= :since")
    long countRecentFailures(java.time.LocalDateTime since);
}