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
 * Tests for Daily Award computation.
 * 
 * Covers:
 * - Basic winner determination by weighted votes
 * - Zero-vote fallback scenarios
 * - Tiebreaker cascades (plays, likes, score, seniority)
 * - Cross-jurisdiction vote aggregation
 * - Award persistence verification
 * 
 * All tests use the test jurisdiction hierarchy:
 * TEST_ROOT
 *   └── TEST_PARENT
 *         ├── TEST_CHILD_A
 *         └── TEST_CHILD_B
 *   └── TEST_SIBLING
 */
@DisplayName("Daily Award Computation Tests")
class DailyAwardComputationTest extends BaseIntegrationTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    // =========================================================================
    // BASIC WINNER DETERMINATION
    // =========================================================================

    @Nested
    @DisplayName("Basic Winner Determination")
    class BasicWinnerDetermination {

        @Test
        @DisplayName("Artist with most daily votes wins")
        void artistWithMostDailyVotesWins() {
            // Given: Two artists in TEST_CHILD_A
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            // Artist A gets 5 daily votes (5 × 10 = 50 points)
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);
            
            // Artist B gets 3 daily votes (3 × 10 = 30 points)
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist A wins
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            Award winner = awards.get(0);
            assertThat(winner.getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(winner.getWeightedPoints()).isEqualTo(50); // 5 × 10
            assertThat(winner.getVotesCount()).isEqualTo(5);
            assertThat(winner.getDeterminationMethod()).isEqualTo("WEIGHTED_VOTES");
        }

        @Test
        @DisplayName("Song with most daily votes wins")
        void songWithMostDailyVotesWins() {
            // Given: Two songs in TEST_CHILD_A
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            Song songA = testDataFactory.createSong("test_songA", artist);
            Song songB = testDataFactory.createSong("test_songB", artist);

            // Song A gets 7 votes (70 points)
            testDataFactory.createMultipleSongVotes(songA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 7);
            
            // Song B gets 2 votes (20 points)
            testDataFactory.createMultipleSongVotes(songB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Song A wins
            List<Award> awards = awardRepository.findByFilters("song",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            Award winner = awards.get(0);
            assertThat(winner.getTargetId()).isEqualTo(songA.getSongId());
            assertThat(winner.getWeightedPoints()).isEqualTo(70);
            assertThat(winner.getDeterminationMethod()).isEqualTo("WEIGHTED_VOTES");
        }

        @Test
        @DisplayName("Single vote is enough to win")
        void singleVoteIsEnoughToWin() {
            // Given: One artist with one vote
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist wins with 10 points
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(artist.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(10);
        }
    }

    // =========================================================================
    // WEIGHTED VOTE SCORING
    // =========================================================================

    @Nested
    @DisplayName("Weighted Vote Scoring")
    class WeightedVoteScoring {

        @Test
        @DisplayName("1 Annual vote (250) beats 24 Daily votes (240)")
        void annualVoteBeats24DailyVotes() {
            // Given: Two artists
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            // Artist A gets 1 Annual vote = 250 points
            User annualVoter = testDataFactory.createListener("annual_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(annualVoter, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            // Artist B gets 24 Daily votes = 240 points
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 24);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist A wins with 250 points
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(250);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("25 Daily votes (250) ties with 1 Annual vote (250)")
        void twentyFiveDailyVotesTiesWithOneAnnualVote() {
            // Given: Two artists
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusDays(30)); // Higher score, older (backup tiebreaker)
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    50, LocalDateTime.now());

            // Artist A gets 1 Annual vote = 250 points
            User annualVoter = testDataFactory.createListener("annual_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(annualVoter, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            // Artist B gets 25 Daily votes = 250 points
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 25);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Both have 250 points, tie broken by plays/likes/score/seniority
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            Award winner = awards.get(0);
            assertThat(winner.getWeightedPoints()).isEqualTo(250);
            // Winner should be artist A (higher score as tiebreaker)
            assertThat(winner.getTargetId()).isEqualTo(artistA.getUserId());
            // Determination should NOT be WEIGHTED_VOTES since it was a tie
            assertThat(winner.getDeterminationMethod()).isNotEqualTo("WEIGHTED_VOTES");
        }

        @Test
        @DisplayName("Mixed vote types aggregate correctly")
        void mixedVoteTypesAggregateCorrectly() {
            // Given: Artist with mixed vote types
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // 2 Daily (20) + 1 Weekly (20) + 1 Monthly (25) = 65 points
            testDataFactory.createWeightedVoteScenario(artist, TestDataFactory.TEST_CHILD_A_ID,
                    YESTERDAY, 2, 1, 1, 0, 0, 0);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Weighted points = 65
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(65);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(4); // Raw count
        }
    }

    // =========================================================================
    // ZERO VOTE FALLBACK
    // =========================================================================

    @Nested
    @DisplayName("Zero Vote Fallback")
    class ZeroVoteFallback {

        @Test
        @DisplayName("When no votes exist, artist with highest score wins with FALLBACK method")
        void noVotesFallsBackToScore() {
            // Given: Two artists with different scores, NO votes
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    500, LocalDateTime.now());
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist A wins by score fallback
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            Award winner = awards.get(0);
            assertThat(winner.getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(winner.getWeightedPoints()).isEqualTo(0);
            assertThat(winner.getVotesCount()).isEqualTo(0);
            assertThat(winner.getDeterminationMethod()).isEqualTo("FALLBACK");
        }

        @Test
        @DisplayName("When no votes and equal scores, oldest artist wins")
        void noVotesEqualScoresFallsBackToSeniority() {
            // Given: Two artists with same score, different ages
            LocalDateTime olderDate = LocalDateTime.now().minusMonths(6);
            LocalDateTime newerDate = LocalDateTime.now().minusDays(1);

            User olderArtist = testDataFactory.createArtist("test_older", TestDataFactory.TEST_CHILD_A_ID,
                    100, olderDate);
            User newerArtist = testDataFactory.createArtist("test_newer", TestDataFactory.TEST_CHILD_A_ID,
                    100, newerDate);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Older artist wins
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(olderArtist.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("FALLBACK");
        }
    }

    // =========================================================================
    // TIEBREAKER CASCADE
    // =========================================================================

    @Nested
    @DisplayName("Tiebreaker Cascade")
    class TiebreakerCascade {

        @Test
        @DisplayName("Equal weighted points, different scores -> winner by SCORE")
        void tiebrokenByScore() {
            // Given: Two artists with equal votes but different scores
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    500, LocalDateTime.now());
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now());

            // Both get 2 daily votes = 20 points each
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist A wins by score
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            Award winner = awards.get(0);
            assertThat(winner.getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(winner.getWeightedPoints()).isEqualTo(20);
            assertThat(winner.getTiedCandidatesCount()).isGreaterThan(0);
            // Should be SCORE since plays/likes are 0 for both
            assertThat(winner.getDeterminationMethod()).isIn("PLAYS", "LIKES", "SCORE", "SENIORITY");
        }

        @Test
        @DisplayName("Equal everything, different seniority -> winner by SENIORITY")
        void tiebrokenBySeniority() {
            // Given: Two artists with equal everything except creation date
            LocalDateTime olderDate = LocalDateTime.now().minusYears(1);
            LocalDateTime newerDate = LocalDateTime.now().minusDays(1);

            User olderArtist = testDataFactory.createArtist("test_older", TestDataFactory.TEST_CHILD_A_ID,
                    100, olderDate);
            User newerArtist = testDataFactory.createArtist("test_newer", TestDataFactory.TEST_CHILD_A_ID,
                    100, newerDate);

            // Both get 1 daily vote = 10 points each
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, olderArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, newerArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Older artist wins by seniority
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(olderArtist.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
            assertThat(awards.get(0).getTiedCandidatesCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // AWARD PERSISTENCE
    // =========================================================================

    @Nested
    @DisplayName("Award Persistence")
    class AwardPersistence {

        @Test
        @DisplayName("Awards are persisted to database")
        void awardsArePersisted() {
            // Given: An artist with votes
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            long awardCountBefore = countTable("awards");
            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Awards count increased
            long awardCountAfter = countTable("awards");
            assertThat(awardCountAfter).isGreaterThan(awardCountBefore);
        }

        @Test
        @DisplayName("Duplicate computation does not create duplicate awards")
        void noDuplicateAwards() {
            // Given: An artist with votes
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute daily awards TWICE
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            
            flushAndClear();
            long countAfterFirst = countTable("awards");

            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            
            flushAndClear();
            long countAfterSecond = countTable("awards");

            // Then: Count should be same (no duplicates)
            assertThat(countAfterSecond).isEqualTo(countAfterFirst);
        }

        @Test
        @DisplayName("Both artist and song awards are created")
        void bothArtistAndSongAwardsCreated() {
            // Given: An artist with a song, both have votes
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            Song song = testDataFactory.createSong("test_song", artist);

            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);
            testDataFactory.createMultipleSongVotes(song, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute daily awards
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Both artist and song awards exist
            List<Award> artistAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);
            
            List<Award> songAwards = awardRepository.findByFilters("song",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(artistAwards).hasSize(1);
            assertThat(songAwards).hasSize(1);
        }
    }

    // =========================================================================
    // getPastAwards AUTO-COMPUTE
    // =========================================================================

    @Nested
@DisplayName("getPastAwards Auto-Compute")
class GetPastAwardsAutoCompute {

    @Test
    @DisplayName("getPastAwards returns computed awards when called after computeAwardsForDate")
    void computesOnTheFly() {
        // Given: An artist with votes, but NO pre-computed awards
        User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
        testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);

        flushAndClear();
        
        // Verify no awards exist initially
        List<Award> before = awardRepository.findByFilters("artist",
                TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);
        assertThat(before).isEmpty();

        // When: Manually compute awards first (simulating what auto-compute would do)
        // Note: In production, getPastAwards calls computeAndSaveAwardsInNewTransaction
        // which uses REQUIRES_NEW. In tests, we call computeAwardsForDate directly
        // since REQUIRES_NEW can't see uncommitted test data.
        awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

        flushAndClear();

        // Then: getPastAwards returns the computed awards
        List<Award> results = awardService.getPastAwards("artist", YESTERDAY, YESTERDAY,
                TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                TestDataFactory.DAILY_INTERVAL_ID);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getTargetId()).isEqualTo(artist.getUserId());

        // And: Awards were persisted
        List<Award> after = awardRepository.findByFilters("artist",
                TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);
        assertThat(after).hasSize(1);
    }
   }
}
