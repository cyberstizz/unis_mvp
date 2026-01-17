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
 * Tests for weighted vote aggregation.
 * 
 * Vote weights per documentation:
 * - Annual = 250 points
 * - Midterm = 200 points
 * - Quarterly = 60 points
 * - Monthly = 25 points
 * - Weekly = 20 points
 * - Daily = 10 points
 */
@DisplayName("Weighted Vote Aggregation Tests")
class WeightedVoteAggregationTest extends BaseIntegrationTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    @Nested
    @DisplayName("Individual Vote Weight Calculations")
    class IndividualVoteWeights {

        @Test
        @DisplayName("Daily vote = 10 points")
        void dailyVoteEquals10Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(10);
        }

        @Test
        @DisplayName("Weekly vote = 20 points")
        void weeklyVoteEquals20Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(20);
        }

        @Test
        @DisplayName("Monthly vote = 25 points")
        void monthlyVoteEquals25Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.MONTHLY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(25);
        }

        @Test
        @DisplayName("Quarterly vote = 60 points")
        void quarterlyVoteEquals60Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.QUARTERLY_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(60);
        }

        @Test
        @DisplayName("Midterm vote = 200 points")
        void midtermVoteEquals200Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.MIDTERM_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(200);
        }

        @Test
        @DisplayName("Annual vote = 250 points")
        void annualVoteEquals250Points() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            User voter = testDataFactory.createListener("test_voter", TestDataFactory.TEST_CHILD_A_ID);
            
            testDataFactory.createArtistVote(voter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(250);
        }
    }

    @Nested
    @DisplayName("Vote Weight Equivalencies")
    class VoteWeightEquivalencies {

        @Test
        @DisplayName("25 Daily votes (250) equals 1 Annual vote (250) - tie scenario")
        void twentyFiveDailyEqualsOneAnnual() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusDays(30));
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID,
                    50, LocalDateTime.now());

            User annualVoter = testDataFactory.createListener("annual_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(annualVoter, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 25);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(250);
            // Should NOT be WEIGHTED_VOTES since it was a tie
            assertThat(awards.get(0).getDeterminationMethod()).isNotEqualTo("WEIGHTED_VOTES");
        }

        @Test
        @DisplayName("1 Annual vote (250) beats 24 Daily votes (240)")
        void oneAnnualBeatsTwentyFourDaily() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            User annualVoter = testDataFactory.createListener("annual_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(annualVoter, artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 24);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(artistA.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(250);
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("WEIGHTED_VOTES");
        }
    }

    @Nested
    @DisplayName("Mixed Vote Type Aggregation")
    class MixedVoteTypeAggregation {

        @Test
        @DisplayName("All vote types aggregate correctly")
        void allVoteTypesAggregate() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // 1 of each: 10 + 20 + 25 + 60 + 200 + 250 = 565
            User voter1 = testDataFactory.createListener("daily_voter", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("weekly_voter", TestDataFactory.TEST_CHILD_A_ID);
            User voter3 = testDataFactory.createListener("monthly_voter", TestDataFactory.TEST_CHILD_A_ID);
            User voter4 = testDataFactory.createListener("quarterly_voter", TestDataFactory.TEST_CHILD_A_ID);
            User voter5 = testDataFactory.createListener("midterm_voter", TestDataFactory.TEST_CHILD_A_ID);
            User voter6 = testDataFactory.createListener("annual_voter", TestDataFactory.TEST_CHILD_A_ID);

            testDataFactory.createArtistVote(voter1, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter2, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter3, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.MONTHLY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter4, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.QUARTERLY_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter5, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.MIDTERM_INTERVAL_ID, YESTERDAY);
            testDataFactory.createArtistVote(voter6, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(565);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("Weighted points matter more than raw vote count")
        void weightedPointsMatterMoreThanRawCount() {
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_A_ID);

            // Artist A: 1 Annual (250) + 1 Monthly (25) + 2 Daily (20) = 295 points, 4 votes
            testDataFactory.createWeightedVoteScenario(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    YESTERDAY, 2, 0, 1, 0, 0, 1);

            // Artist B: 29 Daily votes = 290 points, 29 votes
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 29);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Artist A should win with 295 points despite only 4 votes")
                    .isEqualTo(artistA.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(295);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Song Vote Weights")
    class SongVoteWeights {

        @Test
        @DisplayName("Song vote weights match artist vote weights")
        void songVoteWeightsMatch() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);
            Song song = testDataFactory.createSong("test_song", artist);

            User voter = testDataFactory.createListener("voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createSongVote(voter, song, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.ANNUAL_INTERVAL_ID, YESTERDAY);

            flushAndClear();
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("song",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(250);
        }
    }
}
