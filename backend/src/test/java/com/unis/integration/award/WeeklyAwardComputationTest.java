package com.unis.integration.award;

import com.unis.BaseIntegrationTest;
import com.unis.entity.Award;
import com.unis.entity.User;
import com.unis.fixtures.TestDataFactory;
import org.junit.jupiter.api.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Weekly Award computation.
 * 
 * Weekly awards aggregate votes across an entire week (Monday-Sunday).
 * All vote types cast during that week contribute their weighted points.
 */
@DisplayName("Weekly Award Computation Tests")
class WeeklyAwardComputationTest extends BaseIntegrationTest {

    // Get last Sunday (end of last complete week)
    private static final LocalDate LAST_SUNDAY = LocalDate.now()
            .with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
    
    // Get the Monday that started that week
    private static final LocalDate LAST_MONDAY = LAST_SUNDAY.minusDays(6);

    @Nested
    @DisplayName("Weekly Vote Aggregation")
    class WeeklyVoteAggregation {

        @Test
        @DisplayName("Votes across entire week are aggregated")
        void votesAcrossWeekAreAggregated() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Vote on Monday
            User mondayVoter = testDataFactory.createListener("monday_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(mondayVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY);

            // Vote on Wednesday
            User wednesdayVoter = testDataFactory.createListener("wednesday_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(wednesdayVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY.plusDays(2));

            // Vote on Sunday
            User sundayVoter = testDataFactory.createListener("sunday_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(sundayVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_SUNDAY);

            flushAndClear();

            // Compute WEEKLY awards for the end of the week
            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(awards).hasSize(1);
            // 3 daily votes × 10 points = 30 points
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(30);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Weekly votes have correct weight in weekly awards")
        void weeklyVotesHaveCorrectWeight() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // 1 Weekly vote = 20 points
            User weeklyVoter = testDataFactory.createListener("weekly_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(weeklyVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_MONDAY);

            flushAndClear();

            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(20);
        }

        @Test
        @DisplayName("Mixed vote types aggregate correctly in weekly award")
        void mixedVoteTypesInWeeklyAward() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // 2 Daily (20) + 1 Weekly (20) + 1 Monthly (25) = 65 points
            testDataFactory.createWeightedVoteScenario(artist, TestDataFactory.TEST_CHILD_A_ID,
                    LAST_MONDAY, 2, 1, 1, 0, 0, 0);

            flushAndClear();

            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(65);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Weekly Award Boundaries")
    class WeeklyAwardBoundaries {

        @Test
        @DisplayName("Votes before Monday are NOT included")
        void votesBeforeMondayNotIncluded() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Vote on the Sunday BEFORE the week started (should NOT count)
            User beforeVoter = testDataFactory.createListener("before_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(beforeVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY.minusDays(1));

            // Vote during the actual week (should count)
            User duringVoter = testDataFactory.createListener("during_voter", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(duringVoter, artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY);

            flushAndClear();

            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(awards).hasSize(1);
            // Only 1 vote should count (the one during the week)
            assertThat(awards.get(0).getVotesCount()).isEqualTo(1);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Weekly Tiebreakers")
    class WeeklyTiebreakers {

        @Test
        @DisplayName("Weekly tie broken by seniority")
        void weeklyTieBrokenBySeniority() {
            User olderArtist = testDataFactory.createArtist("test_older", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusYears(2));
            User newerArtist = testDataFactory.createArtist("test_newer", TestDataFactory.TEST_CHILD_A_ID,
                    100, LocalDateTime.now().minusDays(10));

            // Both get 1 weekly vote each = 20 points
            User voter1 = testDataFactory.createListener("voter1", TestDataFactory.TEST_CHILD_A_ID);
            User voter2 = testDataFactory.createListener("voter2", TestDataFactory.TEST_CHILD_A_ID);
            testDataFactory.createArtistVote(voter1, olderArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_MONDAY);
            testDataFactory.createArtistVote(voter2, newerArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_MONDAY);

            flushAndClear();

            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(olderArtist.getUserId());
            assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("SENIORITY");
        }
    }

    @Nested
    @DisplayName("Weekly Award Persistence")
    class WeeklyAwardPersistence {

        @Test
        @DisplayName("Weekly award is persisted separately from daily awards")
        void weeklyAwardPersistedSeparately() {
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY, 3);

            flushAndClear();

            // Compute DAILY award
            awardService.computeAwardsForDate(LAST_MONDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            
            // Compute WEEKLY award
            awardService.computeAwardsForDate(LAST_SUNDAY, TestDataFactory.WEEKLY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Verify both exist
            List<Award> dailyAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, LAST_MONDAY, LAST_MONDAY);
            
            List<Award> weeklyAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.WEEKLY_INTERVAL_ID, LAST_SUNDAY, LAST_SUNDAY);

            assertThat(dailyAwards).hasSize(1);
            assertThat(weeklyAwards).hasSize(1);
            
            // Both should have the same winner
            assertThat(dailyAwards.get(0).getTargetId())
                    .isEqualTo(weeklyAwards.get(0).getTargetId());
        }
    }
}
