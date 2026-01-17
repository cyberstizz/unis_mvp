package com.unis.integration.award;

import com.unis.BaseIntegrationTest;
import com.unis.entity.Award;
import com.unis.entity.User;
import com.unis.fixtures.TestDataFactory;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for jurisdiction hierarchy and vote aggregation.
 * 
 * Test Hierarchy:
 * TEST_ROOT (depth 1)
 *   └── TEST_PARENT (depth 2) - like "Harlem"
 *         ├── TEST_CHILD_A (depth 3) - like "Downtown Harlem"
 *         └── TEST_CHILD_B (depth 3) - like "Uptown Harlem"
 *   └── TEST_SIBLING (depth 2) - like "Brooklyn"
 * 
 * Key Rules Being Tested:
 * 1. Artists compete in their HOME jurisdiction and its CHILDREN only
 * 2. Votes cast in PARENT jurisdictions count for artists in CHILD jurisdictions
 * 3. Votes cast in SIBLING jurisdictions do NOT count
 * 4. Artists from PARENT jurisdiction do NOT appear in CHILD jurisdiction awards
 */
@DisplayName("Jurisdiction Hierarchy Tests")
class JurisdictionHierarchyTest extends BaseIntegrationTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    // =========================================================================
    // BIDIRECTIONAL VOTE AGGREGATION
    // =========================================================================

    @Nested
    @DisplayName("Vote Aggregation Across Hierarchy")
    class VoteAggregation {

        @Test
        @DisplayName("Votes cast in PARENT jurisdiction count for CHILD jurisdiction artists")
        void parentVotesCountForChildArtist() {
            // Given: Artist FROM TEST_CHILD_A (Downtown Harlem)
            User childArtist = testDataFactory.createArtist("test_child_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Votes cast IN TEST_PARENT (Harlem) for the child artist
            testDataFactory.createMultipleArtistVotes(childArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A (Downtown Harlem)
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Child artist wins in child jurisdiction with votes from parent
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(childArtist.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(50); // 5 × 10
        }

        @Test
        @DisplayName("Votes cast in ROOT jurisdiction count for deeply nested artists")
        void rootVotesCountForDeepChild() {
            // Given: Artist FROM TEST_CHILD_A (deeply nested)
            User deepArtist = testDataFactory.createArtist("test_deep_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Votes cast IN TEST_ROOT (top level) for the deep artist
            testDataFactory.createMultipleArtistVotes(deepArtist, TestDataFactory.TEST_ROOT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Deep artist wins with votes from root
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(deepArtist.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(30); // 3 × 10
        }

        @Test
        @DisplayName("Votes from child AND parent jurisdictions aggregate together")
        void votesAggregateFromMultipleLevels() {
            // Given: Artist FROM TEST_CHILD_A
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // 2 votes cast IN TEST_CHILD_A (their home)
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);
            
            // 3 votes cast IN TEST_PARENT (parent)
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);
            
            // 1 vote cast IN TEST_ROOT (grandparent)
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_ROOT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 1);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: All 6 votes aggregate (6 × 10 = 60 points)
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(6);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(60);
        }
    }

    // =========================================================================
    // JURISDICTION ISOLATION
    // =========================================================================

    @Nested
    @DisplayName("Jurisdiction Isolation")
    class JurisdictionIsolation {

        @Test
        @DisplayName("Votes cast in SIBLING jurisdiction do NOT count")
        void siblingVotesDoNotCount() {
            // Given: Artist FROM TEST_CHILD_A
            User childAArtist = testDataFactory.createArtist("test_childA_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Votes cast IN TEST_CHILD_B (sibling jurisdiction) - these should NOT count
            testDataFactory.createMultipleArtistVotes(childAArtist, TestDataFactory.TEST_CHILD_B_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist should have 0 votes (sibling votes don't count)
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            // Either no award (if fallback also finds nothing) or award with 0 weighted points
            if (!awards.isEmpty()) {
                assertThat(awards.get(0).getWeightedPoints()).isEqualTo(0);
                assertThat(awards.get(0).getDeterminationMethod()).isEqualTo("FALLBACK");
            }
        }

        @Test
        @DisplayName("Votes cast in completely unrelated jurisdiction do NOT count")
        void unrelatedVotesDoNotCount() {
            // Given: Artist FROM TEST_CHILD_A
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Votes cast IN TEST_SIBLING (Brooklyn - completely different branch)
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_SIBLING_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 15);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist should have 0 weighted points
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            if (!awards.isEmpty()) {
                assertThat(awards.get(0).getWeightedPoints()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Artist from PARENT does NOT appear in CHILD awards")
        void parentArtistNotInChildAwards() {
            // Given: 
            // - Artist A FROM TEST_PARENT (Harlem) with many votes
            // - Artist B FROM TEST_CHILD_A (Downtown Harlem) with fewer votes
            User parentArtist = testDataFactory.createArtist("test_parent_artist", TestDataFactory.TEST_PARENT_ID);
            User childArtist = testDataFactory.createArtist("test_child_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Parent artist gets 10 votes in parent jurisdiction
            testDataFactory.createMultipleArtistVotes(parentArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);
            
            // Child artist gets only 2 votes
            testDataFactory.createMultipleArtistVotes(childArtist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Child artist wins (parent artist is excluded)
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Parent artist should NOT win child jurisdiction award")
                    .isEqualTo(childArtist.getUserId());
            assertThat(awards.get(0).getTargetId())
                    .isNotEqualTo(parentArtist.getUserId());
        }

        @Test
        @DisplayName("Artist from SIBLING does NOT appear in other sibling awards")
        void siblingArtistNotInOtherSiblingAwards() {
            // Given:
            // - Artist A FROM TEST_CHILD_A (Downtown Harlem)
            // - Artist B FROM TEST_CHILD_B (Uptown Harlem)
            User artistA = testDataFactory.createArtist("test_artistA", TestDataFactory.TEST_CHILD_A_ID);
            User artistB = testDataFactory.createArtist("test_artistB", TestDataFactory.TEST_CHILD_B_ID);

            // Artist B gets many votes in parent (Harlem)
            testDataFactory.createMultipleArtistVotes(artistB, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 20);
            
            // Artist A gets few votes
            testDataFactory.createMultipleArtistVotes(artistA, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 1);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist A wins (Artist B from sibling is excluded)
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId())
                    .as("Sibling jurisdiction artist should NOT win")
                    .isEqualTo(artistA.getUserId());
        }
    }

    // =========================================================================
    // PARENT JURISDICTION AWARDS
    // =========================================================================

    @Nested
    @DisplayName("Parent Jurisdiction Awards")
    class ParentJurisdictionAwards {

        @Test
        @DisplayName("Artists from CHILD jurisdictions compete in PARENT awards")
        void childArtistsCompeteInParentAwards() {
            // Given: Artists from both child jurisdictions
            User childAArtist = testDataFactory.createArtist("test_childA", TestDataFactory.TEST_CHILD_A_ID);
            User childBArtist = testDataFactory.createArtist("test_childB", TestDataFactory.TEST_CHILD_B_ID);

            // Child A artist gets 5 votes in parent
            testDataFactory.createMultipleArtistVotes(childAArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);
            
            // Child B artist gets 3 votes in parent
            testDataFactory.createMultipleArtistVotes(childBArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute awards for TEST_PARENT (Harlem)
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Child A artist wins parent award
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(childAArtist.getUserId());
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(50);
        }

        @Test
        @DisplayName("Parent artist AND child artists all compete in parent awards")
        void parentAndChildCompeteTogether() {
            // Given: One parent artist, two child artists
            User parentArtist = testDataFactory.createArtist("test_parent", TestDataFactory.TEST_PARENT_ID);
            User childAArtist = testDataFactory.createArtist("test_childA", TestDataFactory.TEST_CHILD_A_ID);
            User childBArtist = testDataFactory.createArtist("test_childB", TestDataFactory.TEST_CHILD_B_ID);

            // Parent artist gets most votes
            testDataFactory.createMultipleArtistVotes(parentArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);
            testDataFactory.createMultipleArtistVotes(childAArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 5);
            testDataFactory.createMultipleArtistVotes(childBArtist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute awards for TEST_PARENT
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Parent artist wins
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getTargetId()).isEqualTo(parentArtist.getUserId());
        }
    }

    // =========================================================================
    // COMPLEX SCENARIOS
    // =========================================================================

    @Nested
    @DisplayName("Complex Hierarchy Scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("Same artist can win in multiple jurisdictions simultaneously")
        void artistCanWinMultipleJurisdictions() {
            // Given: Artist from TEST_CHILD_A who is very popular
            User artist = testDataFactory.createArtist("test_popular", TestDataFactory.TEST_CHILD_A_ID);

            // Gets votes at all levels
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_ROOT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 10);

            flushAndClear();

            // When: Compute awards for all jurisdictions
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID);
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_ROOT_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: Artist wins all three
            List<Award> childAAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);
            List<Award> parentAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_PARENT_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);
            List<Award> rootAwards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_ROOT_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(childAAwards).hasSize(1);
            assertThat(parentAwards).hasSize(1);
            assertThat(rootAwards).hasSize(1);
            
            assertThat(childAAwards.get(0).getTargetId()).isEqualTo(artist.getUserId());
            assertThat(parentAwards.get(0).getTargetId()).isEqualTo(artist.getUserId());
            assertThat(rootAwards.get(0).getTargetId()).isEqualTo(artist.getUserId());
        }

        @Test
        @DisplayName("Votes in child award count aggregates from all ancestors")
        void childAwardAggregatesFromAllAncestors() {
            // Given: Artist from TEST_CHILD_A
            User artist = testDataFactory.createArtist("test_artist", TestDataFactory.TEST_CHILD_A_ID);

            // Votes spread across all ancestor jurisdictions
            // 1 vote in child, 2 in parent, 3 in root = 6 total
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_CHILD_A_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 1);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_PARENT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 2);
            testDataFactory.createMultipleArtistVotes(artist, TestDataFactory.TEST_ROOT_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, 3);

            flushAndClear();

            // When: Compute awards for TEST_CHILD_A only
            awardService.computeAwardsForDate(YESTERDAY, TestDataFactory.DAILY_INTERVAL_ID,
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID);

            flushAndClear();

            // Then: All 6 votes are counted
            List<Award> awards = awardRepository.findByFilters("artist",
                    TestDataFactory.TEST_CHILD_A_ID, TestDataFactory.TEST_GENRE_ID,
                    TestDataFactory.DAILY_INTERVAL_ID, YESTERDAY, YESTERDAY);

            assertThat(awards).hasSize(1);
            assertThat(awards.get(0).getVotesCount()).isEqualTo(6);
            assertThat(awards.get(0).getWeightedPoints()).isEqualTo(60); // 6 × 10
        }
    }
}
