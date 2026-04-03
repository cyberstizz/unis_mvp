package com.unis.service;

import com.unis.entity.CronExecution;
import com.unis.repository.CronExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CronMonitorService {

    private final CronExecutionRepository cronExecutionRepository;

    /**
     * Call at the START of any scheduled job.
     * Returns the saved entity — hold onto it and pass to markSuccess/markFailed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CronExecution startExecution(String jobName) {
        CronExecution exec = new CronExecution(jobName);
        return cronExecutionRepository.save(exec);
    }

    /**
     * Call when the job completes successfully.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(CronExecution exec, int awardsCreated) {
        exec.markSuccess(awardsCreated);
        cronExecutionRepository.save(exec);
    }

    /**
     * Call when the job fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(CronExecution exec, String errorMessage) {
        // Truncate error message to avoid DB overflow
        if (errorMessage != null && errorMessage.length() > 2000) {
            errorMessage = errorMessage.substring(0, 2000);
        }
        exec.markFailed(errorMessage);
        cronExecutionRepository.save(exec);
    }

    /**
     * Dashboard: latest execution per job type
     */
    public List<CronExecution> getLatestByEachJob() {
        return cronExecutionRepository.findLatestByEachJob();
    }

    /**
     * Dashboard: last execution for a specific job
     */
    public Optional<CronExecution> getLastExecution(String jobName) {
        return cronExecutionRepository.findTopByJobNameOrderByStartedAtDesc(jobName);
    }

    /**
     * Dashboard: execution history for a specific job
     */
    public List<CronExecution> getJobHistory(String jobName) {
        return cronExecutionRepository.findTop20ByJobNameOrderByStartedAtDesc(jobName);
    }

    /**
     * Dashboard: failure count in last 7 days
     */
    public long getRecentFailureCount() {
        return cronExecutionRepository.countRecentFailures(LocalDateTime.now().minusDays(7));
    }

    /**
     * Dashboard: full status summary
     */
    public Map<String, Object> getStatusSummary() {
        List<CronExecution> latest = getLatestByEachJob();
        long failures = getRecentFailureCount();

        return Map.of(
            "latestExecutions", latest,
            "recentFailures", failures,
            "allHealthy", latest.stream().allMatch(e -> "SUCCESS".equals(e.getStatus()))
        );
    }
}