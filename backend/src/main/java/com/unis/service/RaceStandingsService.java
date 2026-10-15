package com.unis.service;

import com.unis.dto.RaceStandingsDto;
import com.unis.entity.Award;
import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.VotingIntervalRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Live standings for a race, with the midnight rule.
 *
 * <p>A new period starts with everyone at zero. Until anyone in the race scores
 * a point, a board shows the period that just ended instead of a page of
 * zeros:
 * <ul>
 *   <li>If that period has ended for everyone in the race, its final
 *       standings with the recorded winner first ("Final · Yesterday").</li>
 *   <li>If it is still running somewhere in the race (the Unis-wide race at
 *       1 AM New York time, still open in Pacific time), its live standings,
 *       flagged as still closing.</li>
 * </ul>
 * The moment anyone scores in the new period, the board switches to it. The
 * same rule applies to every interval: at 12:05 AM on Monday the weekly board
 * shows last week's final.
 *
 * <p>Points always add up within a period — on a Tuesday the weekly race holds
 * Monday's and Tuesday's points — and start from zero when a new one begins.
 */
@Service
public class RaceStandingsService {

    public static final String LIVE = "LIVE";
    public static final String PREVIOUS_FINAL = "PREVIOUS_FINAL";
    public static final String PREVIOUS_CLOSING = "PREVIOUS_CLOSING";

    /** Which period a board shows, and the order to show it in. */
    public record Resolved(String state,
                           LocalDate[] shown,
                           LocalDate[] current,
                           List<RankingService.Candidate> candidates,
                           UUID winnerId) {}

    private final RankingService rankingService;
    private final RaceClockService raceClock;
    private final VotingIntervalRepository votingIntervalRepository;
    private final AwardRepository awardRepository;
    private final SongRepository songRepository;
    private final UserRepository userRepository;

    public RaceStandingsService(RankingService rankingService,
                                RaceClockService raceClock,
                                VotingIntervalRepository votingIntervalRepository,
                                AwardRepository awardRepository,
                                SongRepository songRepository,
                                UserRepository userRepository) {
        this.rankingService = rankingService;
        this.raceClock = raceClock;
        this.votingIntervalRepository = votingIntervalRepository;
        this.awardRepository = awardRepository;
        this.songRepository = songRepository;
        this.userRepository = userRepository;
    }

    /**
     * The board for a race as seen from a given day (RaceClockService
     * .viewerDateForRace: the viewer's own day, or the race's newest day when
     * signed out). Cached for a minute per day, so everyone on the same day
     * shares it. A null genreId ranks across all genres (Charts page).
     *
     * Each entry carries where it placed in the period before the one shown,
     * for the movement arrows; entries that scored now but not then are new.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "leaderboards",
        key = "'standings-' + #targetType + '-' + #jurisdictionId + '-' + #genreId + '-' "
            + "+ #intervalId + '-' + #limit + '-' + #offset + '-' + #viewDate")
    public RaceStandingsDto standings(String targetType, UUID jurisdictionId, UUID genreId,
                                      UUID intervalId, int limit, int offset, LocalDate viewDate) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown interval: " + intervalId));
        String type = normalizeType(targetType);
        int lim = Math.min(Math.max(limit, 1), 100);
        int off = Math.max(offset, 0);

        Resolved r = resolve(type, jurisdictionId, genreId, interval, viewDate, lim, off);

        // Movement: compare with the period before the one shown, but only if
        // anything happened in it — arrows against a page of zeros mean nothing.
        LocalDate[] before = RaceClockService.previousPeriod(interval.getName(), r.shown());
        List<RankingService.Candidate> beforeTop = rankingService.rankForDisplay(
            type, jurisdictionId, genreId, before[0], before[1], 1, 0);
        boolean beforeScored = !beforeTop.isEmpty() && beforeTop.get(0).totalPoints() > 0;
        Map<UUID, Integer> previousRanks = beforeScored
            ? rankingService.positions(type, jurisdictionId, genreId, before[0], before[1],
                r.candidates().stream().map(RankingService.Candidate::targetId).collect(Collectors.toList()))
            : Map.of();

        int total;
        if (!r.candidates().isEmpty()) {
            total = r.candidates().get(0).totalCount();
        } else {
            List<RankingService.Candidate> first = rankingService.rankForDisplay(
                type, jurisdictionId, genreId, r.shown()[0], r.shown()[1], 1, 0);
            total = first.isEmpty() ? 0 : first.get(0).totalCount();
        }

        String closesAt = null;
        List<String> stillOpenIn = List.of();
        if (LIVE.equals(r.state())) {
            closesAt = raceClock.closesAt(jurisdictionId, r.current()[1]).toString();
        } else if (PREVIOUS_CLOSING.equals(r.state())) {
            closesAt = raceClock.closesAt(jurisdictionId, r.shown()[1]).toString();
            stillOpenIn = raceClock.zonesStillOpen(jurisdictionId, r.shown()[1]).stream()
                .map(ZoneId::getId).collect(Collectors.toList());
        }

        return RaceStandingsDto.builder()
            .type(type)
            .jurisdictionId(jurisdictionId)
            .genreId(genreId)
            .intervalId(intervalId)
            .interval(interval.getName())
            .state(r.state())
            .periodStart(r.shown()[0])
            .periodEnd(r.shown()[1])
            .currentPeriodStart(r.current()[0])
            .currentPeriodEnd(r.current()[1])
            .closesAt(closesAt)
            .stillOpenIn(stillOpenIn)
            .raceTimezone(raceClock.zoneOf(jurisdictionId).getId())
            .limit(lim)
            .offset(off)
            .total(total)
            .entries(hydrate(type, r.candidates(), off, r.winnerId(), previousRanks, beforeScored))
            .build();
    }

    /**
     * Apply the midnight rule for a race as seen from a given day (the board's
     * newest day, or a voter's own day for the voting page).
     */
    public Resolved resolve(String type, UUID jurisdictionId, UUID genreId, VotingInterval interval,
                            LocalDate viewDate, int limit, int offset) {
        String name = interval.getName();
        LocalDate[] current = RaceClockService.periodContaining(name, viewDate);
        List<RankingService.Candidate> currentRank = rankingService.rankForDisplay(
            type, jurisdictionId, genreId, current[0], current[1], limit, offset);

        if (hasActivity(type, jurisdictionId, genreId, current, currentRank, offset)) {
            return new Resolved(LIVE, current, current, currentRank, null);
        }

        LocalDate[] previous = RaceClockService.previousPeriod(name, current);
        List<RankingService.Candidate> previousRank = rankingService.rankForDisplay(
            type, jurisdictionId, genreId, previous[0], previous[1], limit, offset);

        if (!raceClock.isClosed(jurisdictionId, previous[1])) {
            return new Resolved(PREVIOUS_CLOSING, previous, current, previousRank, null);
        }

        // Final: the recorded winner goes first, so the board and the award
        // never disagree (a voter leaving since the close can nudge the numbers).
        UUID winnerId = null;
        List<Award> awards = awardRepository.findByFilters(
            type, jurisdictionId, genreId, interval.getIntervalId(), previous[0], previous[1]);
        if (!awards.isEmpty()) {
            winnerId = awards.get(0).getTargetId();
            if (offset == 0) previousRank = pinFirst(previousRank, winnerId);
        }
        return new Resolved(PREVIOUS_FINAL, previous, current, previousRank, winnerId);
    }

    private boolean hasActivity(String type, UUID jurisdictionId, UUID genreId, LocalDate[] period,
                                List<RankingService.Candidate> page, int offset) {
        List<RankingService.Candidate> top = offset == 0 ? page
            : rankingService.rankForDisplay(type, jurisdictionId, genreId, period[0], period[1], 1, 0);
        return !top.isEmpty() && top.get(0).totalPoints() > 0;
    }

    private static List<RankingService.Candidate> pinFirst(List<RankingService.Candidate> list, UUID targetId) {
        List<RankingService.Candidate> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).targetId().equals(targetId)) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }

    private List<RaceStandingsDto.Entry> hydrate(String type, List<RankingService.Candidate> candidates,
                                                 int offset, UUID winnerId,
                                                 Map<UUID, Integer> previousRanks, boolean compare) {
        List<UUID> ids = candidates.stream().map(RankingService.Candidate::targetId).collect(Collectors.toList());
        Map<UUID, Song> songs = new HashMap<>();
        Map<UUID, User> artists = new HashMap<>();
        if ("song".equals(type)) {
            for (Song s : songRepository.findAllById(ids)) songs.put(s.getSongId(), s);
        } else {
            for (User u : userRepository.findAllById(ids)) artists.put(u.getUserId(), u);
        }

        List<RaceStandingsDto.Entry> entries = new ArrayList<>(candidates.size());
        int rank = offset;
        for (RankingService.Candidate c : candidates) {
            rank++;
            RaceStandingsDto.Entry.EntryBuilder b = RaceStandingsDto.Entry.builder()
                .rank(rank)
                .targetId(c.targetId())
                .targetType(type)
                .totalPoints(c.totalPoints())
                .votePoints(c.weightedPoints())
                .votes(c.rawVoteCount())
                .engagementPoints(c.engagementPoints())
                .plays(c.playsCount())
                .likes(c.likesCount())
                .winner(c.targetId().equals(winnerId));
            Integer previous = previousRanks.get(c.targetId());
            if (previous != null) {
                b.previousRank(previous).movement(previous - rank);
            } else if (compare && c.totalPoints() > 0) {
                b.newEntry(true);
            }
            if ("song".equals(type)) {
                Song s = songs.get(c.targetId());
                if (s != null) {
                    b.name(s.getTitle())
                     .artworkUrl(s.getArtworkUrl())
                     .fileUrl(s.getFileUrl());
                    if (s.getArtist() != null) {
                        b.artistName(s.getArtist().getUsername()).artistId(s.getArtist().getUserId());
                    }
                    Jurisdiction home = s.getJurisdiction() != null ? s.getJurisdiction()
                        : (s.getArtist() != null ? s.getArtist().getJurisdiction() : null);
                    if (home != null) {
                        b.homeJurisdictionId(home.getJurisdictionId()).homeJurisdictionName(home.getName());
                    }
                }
            } else {
                User u = artists.get(c.targetId());
                if (u != null) {
                    b.name(u.getUsername())
                     .artistName(u.getUsername())
                     .artistId(u.getUserId())
                     .artworkUrl(u.getPhotoUrl());
                    if (u.getJurisdiction() != null) {
                        b.homeJurisdictionId(u.getJurisdiction().getJurisdictionId())
                         .homeJurisdictionName(u.getJurisdiction().getName());
                    }
                }
            }
            entries.add(b.build());
        }
        return entries;
    }

    public static String normalizeType(String targetType) {
        return "artist".equalsIgnoreCase(targetType) ? "artist" : "song";
    }
}
