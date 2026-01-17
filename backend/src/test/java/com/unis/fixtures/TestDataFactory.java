package com.unis.fixtures;

import com.unis.entity.*;
import com.unis.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class TestDataFactory {

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private VotingIntervalRepository votingIntervalRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private EntityManager entityManager;

    // =========================================================================
    // WELL-KNOWN TEST IDs
    // =========================================================================

    public static final UUID TEST_ROOT_ID = UUID.fromString("00000000-0000-0000-1000-000000000001");
    public static final UUID TEST_PARENT_ID = UUID.fromString("00000000-0000-0000-1000-000000000002");
    public static final UUID TEST_CHILD_A_ID = UUID.fromString("00000000-0000-0000-1000-000000000003");
    public static final UUID TEST_CHILD_B_ID = UUID.fromString("00000000-0000-0000-1000-000000000004");
    public static final UUID TEST_SIBLING_ID = UUID.fromString("00000000-0000-0000-1000-000000000005");

    public static final UUID TEST_GENRE_ID = UUID.fromString("00000000-0000-0000-2000-000000000001");

    public static final UUID DAILY_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    public static final UUID WEEKLY_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    public static final UUID MONTHLY_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");
    public static final UUID QUARTERLY_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");
    public static final UUID ANNUAL_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");
    public static final UUID MIDTERM_INTERVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000206");

    // =========================================================================
    // BASE FIXTURES SETUP - Using native SQL to avoid Hibernate issues
    // =========================================================================

    public void ensureBaseFixturesExist() {
        createTestJurisdictionsIfNotExist();
        createTestGenreIfNotExist();
        verifyIntervalsExist();
        entityManager.flush();
        entityManager.clear(); // Clear persistence context to avoid stale entities
    }

    private void createTestJurisdictionsIfNotExist() {
        // Use native SQL to insert, avoiding all Hibernate entity state issues
        
        // TEST_ROOT
        if (!jurisdictionRepository.existsById(TEST_ROOT_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO jurisdictions (jurisdiction_id, name, depth, voting_enabled, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5)")
                .setParameter(1, TEST_ROOT_ID)
                .setParameter(2, "TEST_ROOT")
                .setParameter(3, 1)
                .setParameter(4, true)
                .setParameter(5, LocalDateTime.now())
                .executeUpdate();
        }

        // TEST_PARENT
        if (!jurisdictionRepository.existsById(TEST_PARENT_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO jurisdictions (jurisdiction_id, name, parent_jurisdiction_id, depth, voting_enabled, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, TEST_PARENT_ID)
                .setParameter(2, "TEST_PARENT")
                .setParameter(3, TEST_ROOT_ID)
                .setParameter(4, 2)
                .setParameter(5, true)
                .setParameter(6, LocalDateTime.now())
                .executeUpdate();
        }

        // TEST_CHILD_A
        if (!jurisdictionRepository.existsById(TEST_CHILD_A_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO jurisdictions (jurisdiction_id, name, parent_jurisdiction_id, depth, voting_enabled, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, TEST_CHILD_A_ID)
                .setParameter(2, "TEST_CHILD_A")
                .setParameter(3, TEST_PARENT_ID)
                .setParameter(4, 3)
                .setParameter(5, true)
                .setParameter(6, LocalDateTime.now())
                .executeUpdate();
        }

        // TEST_CHILD_B
        if (!jurisdictionRepository.existsById(TEST_CHILD_B_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO jurisdictions (jurisdiction_id, name, parent_jurisdiction_id, depth, voting_enabled, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, TEST_CHILD_B_ID)
                .setParameter(2, "TEST_CHILD_B")
                .setParameter(3, TEST_PARENT_ID)
                .setParameter(4, 3)
                .setParameter(5, true)
                .setParameter(6, LocalDateTime.now())
                .executeUpdate();
        }

        // TEST_SIBLING
        if (!jurisdictionRepository.existsById(TEST_SIBLING_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO jurisdictions (jurisdiction_id, name, parent_jurisdiction_id, depth, voting_enabled, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, TEST_SIBLING_ID)
                .setParameter(2, "TEST_SIBLING")
                .setParameter(3, TEST_ROOT_ID)
                .setParameter(4, 2)
                .setParameter(5, true)
                .setParameter(6, LocalDateTime.now())
                .executeUpdate();
        }
        
        entityManager.flush();
    }

    private void createTestGenreIfNotExist() {
        if (!genreRepository.existsById(TEST_GENRE_ID)) {
            entityManager.createNativeQuery(
                "INSERT INTO genres (genre_id, name) VALUES (?1, ?2)")
                .setParameter(1, TEST_GENRE_ID)
                .setParameter(2, "TEST_RAP")
                .executeUpdate();
            entityManager.flush();
        }
    }

    private void verifyIntervalsExist() {
        if (!votingIntervalRepository.existsById(DAILY_INTERVAL_ID)) {
            throw new IllegalStateException(
                "Daily interval not found! Expected ID: " + DAILY_INTERVAL_ID);
        }
    }

    // =========================================================================
    // ARTIST CREATION - Use repository since these have auto-generated IDs
    // =========================================================================

    public User createArtist(String username, UUID jurisdictionId) {
        return createArtist(username, jurisdictionId, 0, LocalDateTime.now());
    }

    public User createArtist(String username, UUID jurisdictionId, int score, LocalDateTime createdAt) {
        // Fetch fresh references after the clear()
        Jurisdiction jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + jurisdictionId));
        Genre genre = genreRepository.findById(TEST_GENRE_ID)
                .orElseThrow(() -> new RuntimeException("Test genre not found"));

        User artist = User.builder()
                .username(username)
                .email(username + "@test.unis.local")
                .passwordHash("test_hash_not_real") 
                .referralCode("TEST_" + UUID.randomUUID().toString().substring(0, 8))
                .role(User.Role.artist)
                .jurisdiction(jurisdiction)
                .genre(genre)
                .score(score)
                .createdAt(createdAt)
                .build();
        return userRepository.save(artist);
    }

    public User createListener(String username, UUID jurisdictionId) {
        Jurisdiction jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + jurisdictionId));
        Genre genre = genreRepository.findById(TEST_GENRE_ID)
                .orElseThrow(() -> new RuntimeException("Test genre not found"));

        User listener = User.builder()
                .username(username)
                .email(username + "@test.unis.local")
                .passwordHash("test_hash_not_real")  
                .referralCode("TEST_" + UUID.randomUUID().toString().substring(0, 8))
                .role(User.Role.listener)
                .jurisdiction(jurisdiction)
                .genre(genre)
                .createdAt(LocalDateTime.now())
                .build();
        return userRepository.save(listener);
    }

    // =========================================================================
    // SONG CREATION
    // =========================================================================

    public Song createSong(String title, User artist) {
        return createSong(title, artist, 0, LocalDateTime.now());
    }

    public Song createSong(String title, User artist, int score, LocalDateTime createdAt) {
        Genre genre = genreRepository.findById(TEST_GENRE_ID)
                .orElseThrow(() -> new RuntimeException("Test genre not found"));

        Song song = Song.builder()
                .title(title)
                .artist(artist)
                .genre(genre)
                .jurisdiction(artist.getJurisdiction())
                .score(score)
                .createdAt(createdAt)
                .build();
        return songRepository.save(song);
    }

    // =========================================================================
    // VOTE CREATION
    // =========================================================================

    public Vote createArtistVote(User voter, User target, UUID jurisdictionId, UUID intervalId, LocalDate voteDate) {
        Jurisdiction jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + jurisdictionId));
        Genre genre = genreRepository.findById(TEST_GENRE_ID)
                .orElseThrow(() -> new RuntimeException("Test genre not found"));
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
                .orElseThrow(() -> new RuntimeException("Interval not found: " + intervalId));

        Vote vote = Vote.builder()
                .user(voter)
                .targetType("artist")
                .targetId(target.getUserId())
                .jurisdiction(jurisdiction)
                .genre(genre)
                .interval(interval)
                .voteDate(voteDate)
                .build();
        return voteRepository.save(vote);
    }

    public Vote createSongVote(User voter, Song target, UUID jurisdictionId, UUID intervalId, LocalDate voteDate) {
        Jurisdiction jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + jurisdictionId));
        Genre genre = genreRepository.findById(TEST_GENRE_ID)
                .orElseThrow(() -> new RuntimeException("Test genre not found"));
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
                .orElseThrow(() -> new RuntimeException("Interval not found: " + intervalId));

        Vote vote = Vote.builder()
                .user(voter)
                .targetType("song")
                .targetId(target.getSongId())
                .jurisdiction(jurisdiction)
                .genre(genre)
                .interval(interval)
                .voteDate(voteDate)
                .build();
        return voteRepository.save(vote);
    }

    public void createMultipleArtistVotes(User target, UUID jurisdictionId, UUID intervalId,
                                           LocalDate voteDate, int count) {
        for (int i = 0; i < count; i++) {
            User voter = createListener("voter_" + UUID.randomUUID().toString().substring(0, 8), jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, intervalId, voteDate);
        }
    }

    public void createMultipleSongVotes(Song target, UUID jurisdictionId, UUID intervalId,
                                         LocalDate voteDate, int count) {
        for (int i = 0; i < count; i++) {
            User voter = createListener("voter_" + UUID.randomUUID().toString().substring(0, 8), jurisdictionId);
            createSongVote(voter, target, jurisdictionId, intervalId, voteDate);
        }
    }

    public void createWeightedVoteScenario(User target, UUID jurisdictionId, LocalDate voteDate,
                                           int dailyVotes, int weeklyVotes, int monthlyVotes,
                                           int quarterlyVotes, int midtermVotes, int annualVotes) {
        for (int i = 0; i < dailyVotes; i++) {
            User voter = createListener("daily_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, DAILY_INTERVAL_ID, voteDate);
        }
        for (int i = 0; i < weeklyVotes; i++) {
            User voter = createListener("weekly_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, WEEKLY_INTERVAL_ID, voteDate);
        }
        for (int i = 0; i < monthlyVotes; i++) {
            User voter = createListener("monthly_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, MONTHLY_INTERVAL_ID, voteDate);
        }
        for (int i = 0; i < quarterlyVotes; i++) {
            User voter = createListener("quarterly_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, QUARTERLY_INTERVAL_ID, voteDate);
        }
        for (int i = 0; i < midtermVotes; i++) {
            User voter = createListener("midterm_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, MIDTERM_INTERVAL_ID, voteDate);
        }
        for (int i = 0; i < annualVotes; i++) {
            User voter = createListener("annual_voter_" + i, jurisdictionId);
            createArtistVote(voter, target, jurisdictionId, ANNUAL_INTERVAL_ID, voteDate);
        }
    }
}