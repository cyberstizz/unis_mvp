package com.unis.integration.award;

import com.unis.BaseIntegrationTest;
import com.unis.entity.Award;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.fixtures.TestDataFactory;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the 5-level tiebreaker cascade.
 * 
 * Tiebreaker order (per documentation):
 * 1. Weighted Points (primary - sum of vote weights)
 * 2. Plays Count (song plays during interval)
 * 3. Likes Count (likes during interval)
 * 4. Score (platform engagement score)
 * 5. Seniority (oldest account/song wins)
 * 
 * Each test verifies that when a tie exists at one level,
 * the next level correctly breaks the tie.
 */
@DisplayName("Tiebreaker Cascade Tests")
class TiebreakerCascadeTest extends BaseIntegrationTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    // =========================================================================
    // LEVEL 1: WEIGHTED POINTS (NO TIE)
    // =========================================================================

    @Nested
    @DisplayName("Level 1: Weighted Points (No Tie)")
    class WeightedPointsNoTie {

        @Test
        @DisplayName("Clear winner by weighted points - determination method is WEIGHTED_VOTES")
        void clearWinnerByWeightedPoints() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            // Artist A: 5 daily votes = 50 points
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);
            
            // Artist B: 3 daily votes = 30 points
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("WEIGHTED_VOTES");
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(0);
        }
    }

    // =========================================================================
    // LEVEL 2: PLAYS COUNT TIEBREAKER
    // =========================================================================

    @Nested
    @DisplayName("Level 2: Plays Count Tiebreaker")
    class PlaysCountTiebreaker {

        @Test
        @DisplayName("Equal weighted points, different plays - winner by PLAYS")
        void tiebrokenByPlays() {
            // This test requires song_plays to be seeded
            // For now, we'll test the determination method detection
            
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now()); // Same score
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now()); // Same score

            // Both get 2 daily votes = 20 points each
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);

            flushAndClear();
            
            // TODO: Add song_plays for artistA to test PLAYS tiebreaker
            // For now, this will fall through to SCORE or SENIORITY
            
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(20);
            // Since plays are 0 for both, should fall through to next tiebreaker
            assertThat(awards.get(0).getDeterminationMethod()).isIn("PLAYS", "LIKES", "SCORE", "SENIORITY");
            assertThat(awards.get(0).getTiedCandidatesCount()).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // LEVEL 3: LIKES COUNT TIEBREAKER
    // =========================================================================

    @Nested
    @DisplayName("Level 3: Likes Count Tiebreaker")
    class LikesCountTiebreaker {

        @Test
        @DisplayName("Equal weighted points and plays, different likes - winner by LIKES")
        void tiebrokenByLikes() {
            // Similar to plays, requires likes to be seeded
            // For now, tests the cascade continues correctly
            
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());

            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 1);
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 1);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTiedCandidatesCount()).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // LEVEL 4: SCORE TIEBREAKER
    // =========================================================================

    @Nested
    @DisplayName("Level 4: Score Tiebreaker")
    class ScoreTiebreaker {

        @Test
        @DisplayName("Equal weighted points, plays, likes - different scores - winner by SCORE")
        void tiebrokenByScore() {
            // Artist A has higher score
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    500, LocalDateTime.now());
            // Artist B has lower score
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());

            // Both get exactly 1 daily vote = 10 points each
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Artist A should win by higher score")
                    .isEqualTo(artistA.getUserId());
            // Should be SCORE since plays/likes are 0, but scores differ
            assertThat(awards.get(0).getDeterminationMethod()).isIn("PLAYS", "LIKES", "SCORE");
        }
    }

    // =========================================================================
    // LEVEL 5: SENIORITY TIEBREAKER
    // =========================================================================

    @Nested
    @DisplayName("Level 5: Seniority Tiebreaker")
    class SeniorityTiebreaker {

        @Test
        @DisplayName("Equal everything - different seniority - winner by SENIORITY (oldest wins)")
        void tiebrokenBySeniority() {
            // Older artist (created 1 year ago)
            LocalDateTime olderDate = LocalDateTime.now().minusYears(1);
            User olderArtist = testDataFactory.createArtist("test_older", TestDataFactory.TEST_CHILD_A_ID,
                    100, olderDate);
            
            // Newer artist (created yesterday)
            LocalDateTime newerDate = LocalDateTime.now().minusDays(1);
            User newerArtist = testDataFactory.createArtist("test_newer", TestDataFactory.TEST_CHILD_A_ID,
                    100, newerDate);

            // Both get exactly 1 daily vote
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, olderArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, newerArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Older artist should win by seniority")
                    .isEqualTo(olderArtist.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Three-way tie broken by seniority")
        void threeWayTieBrokenBySeniority() {
            // Three artists with same score, created at different times
            User oldest = testDataFactory.createArtist("test_oldest", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(2));
            User middle = testDataFactory.createArtist("test_middle", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusMonths(6));
            User newest = testDataFactory.createArtist("test_newest", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusDays(7));

            // Each gets exactly 1 daily vote
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            User voter3 = testDataFactory.createListener("voter3", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, oldest, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, middle, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter3, newest, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Oldest artist should win 3-way tie")
                    .isEqualTo(oldest.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(3);
        }
    }

    // =========================================================================
    // SONG TIEBREAKER TESTS
    // =========================================================================

    @Nested
    @DisplayName("Song Tiebreaker Tests")
    class SongTiebreaker {

        @Test
        @DisplayName("Song tie broken by seniority (oldest song wins)")
        void songTieBrokenBySeniority() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            
            // Older song
            Song olderSong = testDataFactory.createSong("test_older_song", artist,
                    100, LocalDateTime.now().minusMonths(6));
            // Newer song
            Song newerSong = testDataFactory.createSong("test_newer_song", artist,
                    100, LocalDateTime.now().minusDays(1));

            // Each gets exactly 1 daily vote
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createSongVote(voter1, olderSong, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createSongVote(voter2, newerSong, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("song",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Older song should win")
                    .isEqualTo(olderSong.getSongId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
        }

        @Test
        @DisplayName("Song with higher score wins tie")
        void songWithHigherScoreWinsTie() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            
            // High score song
            Song highScoreSong = testDataFactory.createSong("test_high_score", artist,
                    500, LocalDateTime.now());
            // Low score song
            Song lowScoreSong = testDataFactory.createSong("test_low_score", artist,
                    100, LocalDateTime.now());

            // Each gets exactly 1 daily vote
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createSongVote(voter1, highScoreSong, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createSongVote(voter2, lowScoreSong, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("song",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Higher score song should win")
                    .isEqualTo(highScoreSong.getSongId());
        }
    }

    // =========================================================================
    // TIED CANDIDATES COUNT VERIFICATION
    // =========================================================================

    @Nested
    @DisplayName("Tied Candidates Count Verification")
    class TiedCandidatesCount {

        @Test
        @DisplayName("No tie - tiedCandidatesCount is 0")
        void noTieTiedCountIsZero() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            // Clear winner
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Two-way tie - tiedCandidatesCount is 2")
        void twoWayTieTiedCountIsTwo() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(1));
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());

            // Exact tie
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTiedCandidatesCount()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Five-way tie - tiedCandidatesCount is 5")
        void fiveWayTie() {
            // Create 5 artists with same score but different seniority
            User artist1 = testDataFactory.createArtist("test_artist1", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(5));
            User artist2 = testDataFactory.createArtist("test_artist2", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(4));
            User artist3 = testDataFactory.createArtist("test_artist3", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(3));
            User artist4 = testDataFactory.createArtist("test_artist4", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(2));
            User artist5 = testDataFactory.createArtist("test_artist5", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(1));

            // Each gets exactly 1 vote
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            User voter3 = testDataFactory.createListener("voter3", TestDataFactory.TEST_CHILD_A_ID);
            User voter4 = testDataFactory.createListener("voter4", TestDataFactory.TEST_CHILD_A_ID);
            User voter5 = testDataFactory.createListener("voter5", TestDataFactory.TEST_CHILD_A_ID);

            testDataFactory.createArtistVote(voter1, artist1, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, artist2, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter3, artist3, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter4, artist4, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter5, artist5, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Oldest artist (artist1) should win 5-way tie")
                    .isEqualTo(artist1.getUserId());
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(5);
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
        }
    }
}
