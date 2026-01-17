package com.unis;

import com.unis.repository.*;
import com.unis.service.AwardService;
import com.unis.service.ScoreUpdateService;
import com.unis.fixtures.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Base class for all integration tests.
 * 
 * Uses your existing PostgreSQL database (no Docker required).
 * All tests are wrapped in @Transactional which means all changes
 * are ROLLED BACK after each test - your real data is never affected.
 * 
 * Requirements:
 * 1. Your local PostgreSQL must be running
 * 2. application-test.yml must point to your database
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Transactional  // CRITICAL: This ensures all test data is rolled back after each test
public abstract class BaseIntegrationTest {

    // =========================================================================
    // INJECTED DEPENDENCIES
    // =========================================================================

    @Autowired
    protected AwardService awardService;

    @Autowired
    protected ScoreUpdateService scoreUpdateService;

    @Autowired
    protected AwardRepository awardRepository;

    @Autowired
    protected VoteRepository voteRepository;

    @Autowired
    protected VotingIntervalRepository votingIntervalRepository;

    @Autowired
    protected JurisdictionRepository jurisdictionRepository;

    @Autowired
    protected GenreRepository genreRepository;

    @Autowired
    protected SongRepository songRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected TestDataFactory testDataFactory;

    // =========================================================================
    // SETUP
    // =========================================================================

    @BeforeEach
    void baseSetUp() {
        // Ensure test fixtures exist (test jurisdictions, genres, intervals)
        // These use fixed UUIDs so they won't create duplicates
        testDataFactory.ensureBaseFixturesExist();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Flush and clear the EntityManager to ensure we're reading fresh data from DB.
     * Call this after writes and before assertions.
     */
    protected void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Execute a count query and return the result
     */
    protected long countTable(String tableName) {
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM " + tableName)
                .getSingleResult())
                .longValue();
    }

    /**
     * Print debug info about current database state
     */
    protected void debugPrintState() {
        System.out.println("=== DEBUG: Current Database State ===");
        System.out.println("Jurisdictions: " + countTable("jurisdictions"));
        System.out.println("Genres: " + countTable("genres"));
        System.out.println("Intervals: " + countTable("voting_intervals"));
        System.out.println("Users: " + countTable("users"));
        System.out.println("Songs: " + countTable("songs"));
        System.out.println("Votes: " + countTable("votes"));
        System.out.println("Awards: " + countTable("awards"));
        System.out.println("=====================================");
    }
}
