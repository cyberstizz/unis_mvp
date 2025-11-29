package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "playlist_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistTrack {
    @Id
    @GeneratedValue
    @Column(name = "playlist_item_id", columnDefinition = "UUID")
    private UUID playlistItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", referencedColumnName = "playlist_id")
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id", columnDefinition = "UUID")
    private Song song;

    @Column(name = "position")
    private Integer position;

    @Column(name = "added_at")
    private LocalDateTime addedAt = LocalDateTime.now();
}
