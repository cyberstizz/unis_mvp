package com.unis.service;

import com.unis.dto.ChartsDto;
import com.unis.entity.Song;
import com.unis.repository.ChartsRepository;
import com.unis.repository.SongRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the monthly "top voted" chart for the Feed Charts lens.
 *
 * Current month = 1st of the month through today.
 * Previous month = the full previous calendar month (used only to
 * compute rank movement arrows).
 */
@Service
public class ChartsService {

    private final ChartsRepository chartsRepository;
    private final SongRepository songRepository;

    public ChartsService(ChartsRepository chartsRepository, SongRepository songRepository) {
        this.chartsRepository = chartsRepository;
        this.songRepository = songRepository;
    }

    public ChartsDto getMonthlyChart(UUID jurisdictionId, int limit) {
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);
        YearMonth lastMonth = thisMonth.minusMonths(1);

        LocalDate currentStart = thisMonth.atDay(1);
        LocalDate prevStart = lastMonth.atDay(1);
        LocalDate prevEnd = lastMonth.atEndOfMonth();

        // ── Current month ranking ──
        List<Object[]> currentRows =
                chartsRepository.findSongVoteCountsForRange(jurisdictionId, currentStart, today);

        // ── Previous month ranking (songId -> rank) for movement ──
        List<Object[]> prevRows =
                chartsRepository.findSongVoteCountsForRange(jurisdictionId, prevStart, prevEnd);

        Map<UUID, Integer> prevRanks = new HashMap<>();
        for (int i = 0; i < prevRows.size(); i++) {
            prevRanks.put(toUuid(prevRows.get(i)[0]), i + 1);
        }

        // ── Total votes this month (all target types) ──
        Long totalVotes =
                chartsRepository.countVotesForRange(jurisdictionId, currentStart, today);

        // ── Hydrate songs in one query ──
        List<UUID> songIds = currentRows.stream()
                .limit(limit)
                .map(row -> toUuid(row[0]))
                .collect(Collectors.toList());

        Map<UUID, Song> songsById = songRepository.findAllById(songIds).stream()
                .collect(Collectors.toMap(Song::getSongId, Function.identity()));

        List<ChartsDto.ChartEntry> entries = new ArrayList<>();
        int rank = 0;

        for (Object[] row : currentRows) {
            if (entries.size() >= limit) break;

            UUID songId = toUuid(row[0]);
            long votes = ((Number) row[1]).longValue();

            Song song = songsById.get(songId);
            // Skip songs that were deleted or otherwise missing
            if (song == null || song.getDeletedAt() != null) continue;

            rank++;

            Integer prevRank = prevRanks.get(songId);
            Integer movement = (prevRank == null) ? null : prevRank - rank;

            entries.add(ChartsDto.ChartEntry.builder()
                    .rank(rank)
                    .movement(movement)
                    .votes(votes)
                    .songId(songId)
                    .title(song.getTitle())
                    .artworkUrl(song.getArtworkUrl())
                    .fileUrl(song.getFileUrl())
                    .duration(song.getDuration())
                    .explicit(song.getExplicit())
                    .artistId(song.getArtist() != null ? song.getArtist().getUserId() : null)
                    .artistName(song.getArtist() != null ? song.getArtist().getUsername() : "Unknown")
                    .build());
        }

        return ChartsDto.builder()
                .month(thisMonth.toString())
                .totalVotesThisMonth(totalVotes != null ? totalVotes : 0L)
                .entries(entries)
                .build();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID) return (UUID) value;
        return UUID.fromString(String.valueOf(value));
    }
}