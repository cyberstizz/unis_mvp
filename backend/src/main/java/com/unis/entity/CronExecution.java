package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cron_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CronExecution {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "execution_id", updatable = false, nullable = false)
    private UUID executionId;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "awards_created")
    @Builder.Default
    private Integer awardsCreated = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    // Convenience constructor for starting a new execution
    public CronExecution(String jobName) {
        this.jobName = jobName;
        this.startedAt = LocalDateTime.now();
        this.status = "RUNNING";
        this.awardsCreated = 0;
    }

    public void markSuccess(int awardsCreated) {
        this.status = "SUCCESS";
        this.awardsCreated = awardsCreated;
        this.finishedAt = LocalDateTime.now();
        this.durationMs = java.time.Duration.between(this.startedAt, this.finishedAt).toMillis();
    }

    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
        this.durationMs = java.time.Duration.between(this.startedAt, this.finishedAt).toMillis();
    }
}