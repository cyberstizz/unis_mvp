package com.service;

import com.unis.entity.Award;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.service.AwardService;
import com.unis.service.ScoreUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwardServiceTest {

    @InjectMocks
    private AwardService awardService;

    @Mock
    private AwardRepository awardRepository;

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private VotingIntervalRepository votingIntervalRepository;

    @Mock
    private JurisdictionRepository jurisdictionRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private ScoreUpdateService scoreUpdateService;

    private UUID testIntervalId;
    private UUID testJurisdictionId;
    private UUID testGenreId;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        testIntervalId = UUID.randomUUID();
        testJurisdictionId = UUID.randomUUID();
        testGenreId = UUID.randomUUID();
        testDate = LocalDate.now();
    }

    @Test
    void testGetLeaderboards() {
        List<Award> mockAwards = List.of(Award.builder().votesCount(10).build());
        when(awardRepository.findTopByPeriod(eq(testJurisdictionId), eq(testIntervalId), any(LocalDate.class), any(LocalDate.class))).thenReturn(mockAwards);
        List<Award> result = awardService.getLeaderboards("song", testIntervalId, testJurisdictionId);

        assertEquals(1, result.size());
        verify(awardRepository).findTopByPeriod(eq(testJurisdictionId), eq(testIntervalId), any(LocalDate.class), any(LocalDate.class));    }

    @Test
    void testGetPastAwards() {
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        List<Award> mockAwards = List.of(Award.builder().votesCount(5).build());
        when(awardRepository.findTopByPeriod(testJurisdictionId, null, start, end)).thenReturn(mockAwards);

        List<Award> result = awardService.getPastAwards("song", start, end, testJurisdictionId);

        assertEquals(1, result.size());
        verify(awardRepository).findTopByPeriod(testJurisdictionId, null, start, end);
    }

    @Test
void testComputeDailyAwardsForDate() {
    // Mock the "Daily" interval lookup that happens first
    VotingInterval mockDailyInterval = VotingInterval.builder()
        .intervalId(testIntervalId)
        .name("Daily")
        .build();
    when(votingIntervalRepository.findByName("Daily")).thenReturn(Optional.of(mockDailyInterval));
    
    // Keep your existing mocks
    when(jurisdictionRepository.findAllJurisdictionIds()).thenReturn(List.of(testJurisdictionId));
    when(genreRepository.findAllGenreIds()).thenReturn(List.of(testGenreId));
    List<Object[]> mockTopVotes = Arrays.<Object[]>asList(new Object[]{UUID.randomUUID(), 5});
    when(voteRepository.findTopVoteCountsForDate(testJurisdictionId, testIntervalId, testDate)).thenReturn(mockTopVotes);
    Genre mockGenre = Genre.builder().genreId(testGenreId).build();
    when(genreRepository.findById(testGenreId)).thenReturn(Optional.of(mockGenre));
    Jurisdiction mockJurisdiction = Jurisdiction.builder().jurisdictionId(testJurisdictionId).build();
    when(jurisdictionRepository.findById(testJurisdictionId)).thenReturn(Optional.of(mockJurisdiction));
    VotingInterval mockDaily = VotingInterval.builder().intervalId(testIntervalId).build();
    when(votingIntervalRepository.findById(testIntervalId)).thenReturn(Optional.of(mockDaily));
    Award mockAward = Award.builder().votesCount(5).build();
    when(awardRepository.save(any(Award.class))).thenReturn(mockAward);

    awardService.computeDailyAwardsForDate(testDate);

    verify(voteRepository).findTopVoteCountsForDate(testJurisdictionId, testIntervalId, testDate);
    verify(awardRepository).save(any(Award.class));
    verify(scoreUpdateService).onAward(any(UUID.class), eq(100));
    }
}