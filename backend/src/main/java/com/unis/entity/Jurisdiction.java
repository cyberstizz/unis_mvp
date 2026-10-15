package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "jurisdictions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
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
    private String bio;

    @Column(name = "symbol_url")
    private String symbolUrl;

    @Column
    private Integer depth;

    @Column
    private String path;

    /**
     * The real-world clock this jurisdiction runs on (IANA name, e.g.
     * America/New_York). Read-only here: set in SQL, and new jurisdictions
     * inherit their parent's automatically (sql/V2026_10e__race_clock.sql).
     */
    @Column(name = "timezone", insertable = false, updatable = false)
    private String timezone;

    @Column(name = "voting_enabled")
    @Builder.Default
    private Boolean votingEnabled = false;
}