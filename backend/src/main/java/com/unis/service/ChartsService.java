package com.unis.service;

import com.unis.dto.ChartsDto;
import com.unis.entity.Song;
import com.unis.repository.ChartsRepository;
import com.unis.repository.SongRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the "Most Played This Week" chart for the Feed Charts lens.
 *
 * Current window  = the last 7 days (rolling, up to now).
 * Previous window = the 7 days before that (used only to compute
 *                   rank movement arrows).
 *
 * Play-based rather than vote-based so the chart shows real content
 * from day one, even with a small user base.
 */
@Service
public class ChartsService {

    private final ChartsRepository chartsRepository;
    private final SongRepository songRepository;

    public ChartsService(ChartsRepository chartsRepository, SongRepository songRepository) {
        this.chartsRepository = chartsRepository;
        this.songRepository = songRepository;
    }

    public ChartsDto getWeeklyChart(UUID jurisdictionId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        LocalDateTime twoWeeksAgo = now.minusDays(14);

        // ── Current window ranking (last 7 days) ──
        List<Object[]> currentRows =
                chartsRepository.findSongPlayCountsForRange(jurisdictionId, weekAgo, now);

        // ── Previous window ranking (songId -> rank) for movement ──
        List<Object[]> prevRows =
                chartsRepository.findSongPlayCountsForRange(jurisdictionId, twoWeeksAgo, weekAgo);

        Map<UUID, Integer> prevRanks = new HashMap<>();
        for (int i = 0; i < prevRows.size(); i++) {
            prevRanks.put(toUuid(prevRows.get(i)[0]), i + 1);
        }

        // ── Total plays this week ──
        Long totalPlays =
                chartsRepository.countPlaysForRange(jurisdictionId, weekAgo, now);

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
            long plays = ((Number) row[1]).longValue();

            Song song = songsById.get(songId);
            // Skip songs that were deleted or otherwise missing
            if (song == null || song.getDeletedAt() != null) continue;

            rank++;

            Integer prevRank = prevRanks.get(songId);
            Integer movement = (prevRank == null) ? null : prevRank - rank;

            entries.add(ChartsDto.ChartEntry.builder()
                    .rank(rank)
                    .movement(movement)
                    .plays(plays)
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
                .totalPlaysThisWeek(totalPlays != null ? totalPlays : 0L)
                .entries(entries)
                .build();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID) return (UUID) value;
        return UUID.fromString(String.valueOf(value));
    }
}