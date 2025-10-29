package com.service;

import com.unis.entity.Vote;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.service.ScoreUpdateService;
import com.unis.service.VoteService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    @InjectMocks
    private VoteService voteService;

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private AwardRepository awardRepository;

    @Mock
    private ScoreUpdateService scoreUpdateService;

    private Vote testVote;
    private UUID testUserId;
    private UUID testTargetId;
    private UUID testGenreId;
    private UUID testJurisdictionId;
    private UUID testIntervalId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testTargetId = UUID.randomUUID();
        testGenreId = UUID.randomUUID();
        testJurisdictionId = UUID.randomUUID();
        testIntervalId = UUID.randomUUID();

        testVote = Vote.builder()
            .user(User.builder().userId(testUserId).build())
            .targetType("song")
            .targetId(testTargetId)
            .genre(Genre.builder().genreId(testGenreId).build())
            .jurisdiction(Jurisdiction.builder().jurisdictionId(testJurisdictionId).build())
            .interval(VotingInterval.builder().intervalId(testIntervalId).build())
            .voteDate(LocalDate.now())
            .build();
    }

    @Test
    void testSubmitVote() {
        when(voteRepository.existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
                testUserId, "song", testTargetId, testGenreId, testJurisdictionId, testIntervalId, testVote.getVoteDate())).thenReturn(0L);
        when(voteRepository.save(testVote)).thenReturn(testVote);

        Vote saved = voteService.submitVote(testVote);

        assertNotNull(saved);
        verify(voteRepository).save(testVote);
        verify(scoreUpdateService).onVote(testUserId, testTargetId, "song");
        verify(awardRepository).incrementAwardEngagement("song", testTargetId, testJurisdictionId, testIntervalId);
    }

    @Test
    void testSubmitVoteDuplicate() {
        when(voteRepository.existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
                testUserId, "song", testTargetId, testGenreId, testJurisdictionId, testIntervalId, testVote.getVoteDate())).thenReturn(1L);

        assertThrows(RuntimeException.class, () -> voteService.submitVote(testVote));
    }

    @Test
    void testGetTotalVotesForTarget() {
        Long expected = 5L;
        when(voteRepository.countByTarget("song", testTargetId)).thenReturn(expected);

        Long total = voteService.getTotalVotesForTarget("song", testTargetId);

        assertEquals(expected, total);
    }

    @Test
    void testGetVotesCastByUser() {
        Long expected = 3L;
        when(voteRepository.countByUserId(testUserId)).thenReturn(expected);

        Long count = voteService.getVotesCastByUser(testUserId);

        assertEquals(expected, count);
    }
}