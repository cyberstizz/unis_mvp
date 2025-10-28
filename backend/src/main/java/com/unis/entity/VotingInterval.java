package com.unis.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "voting_intervals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})  // Ignore proxy internals for JSON
public class VotingInterval {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID intervalId;

    @Column(name = "name", nullable = false)
    private String name;  // "Daily", "Weekly", etc.

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}