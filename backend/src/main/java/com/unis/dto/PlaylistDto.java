package com.unis.dto;

import com.unis.entity.Playlist;
import com.unis.entity.PlaylistTrack;
import com.unis.entity.Song;
import lombok.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistDto {
    // Use UUID to match playlist.playlist_id
    private UUID playlistId;
    private String name;
    private List<TrackDto> tracks;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TrackDto {
        // playlist_item id is a UUID
        private UUID playlistItemId;
        private UUID songId;
        private String title;
        private String artistName;
        private String artworkUrl;
        private Integer duration;
        private String fileUrl;
    }

    public static PlaylistDto fromEntity(Playlist p) {
        if (p == null) return null;

        List<TrackDto> tracks = (p.getItems() == null) ? List.of() :
            p.getItems().stream().map(pi -> {
                Song s = pi.getSong();
                return TrackDto.builder()
                        .playlistItemId(pi.getPlaylistItemId())
                        .songId(s != null ? s.getSongId() : null)
                        .title(s != null ? s.getTitle() : null)
                        .artistName((s != null && s.getArtist() != null) ? s.getArtist().getUsername() : null)
                        .artworkUrl(s != null ? s.getArtworkUrl() : null)
                        .duration(s != null ? s.getDuration() : null)
                        .fileUrl(s != null ? s.getFileUrl() : null)
                        .build();
            }).collect(Collectors.toList());

        return PlaylistDto.builder()
                .playlistId(p.getPlaylistId())
                .name(p.getName())
                .tracks(tracks)
                .build();
    }
}
