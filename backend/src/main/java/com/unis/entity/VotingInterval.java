package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "voting_intervals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VotingInterval {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID intervalId;

    @Column(nullable = false)
    private String name;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;
}