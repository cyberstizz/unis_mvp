package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "jurisdictions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Jurisdiction {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID jurisdictionId;

    @Column(nullable = false)
    private String name;

    @Column
    private String polygon;  // PostGIS placeholder

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_jurisdiction_id")
    private Jurisdiction parentJurisdiction;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private String bio;  // Added for page 8
}