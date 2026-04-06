package com.unis.service;

import com.unis.dto.PlaylistDtos.*;
import com.unis.entity.*;
import com.unis.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final PlaylistFollowRepository playlistFollowRepository;
    private final PlaylistVoteRepository playlistVoteRepository;
    private final PlaylistActivityRepository playlistActivityRepository;
    private final BlockedSongRepository blockedSongRepository;
    private final SongRepository songRepository;
    private final UserRepository userRepository;
    private final JurisdictionRepository jurisdictionRepository;
    private final ScoreUpdateService scoreUpdateService;

    // Community playlist vote thresholds
    private static final int APPROVE_THRESHOLD = 5;   // net +5 to auto-approve
    private static final int REJECT_THRESHOLD = -3;    // net -3 to auto-reject

    public PlaylistService(PlaylistRepository playlistRepository,
                           PlaylistTrackRepository playlistTrackRepository,
                           PlaylistFollowRepository playlistFollowRepository,
                           PlaylistVoteRepository playlistVoteRepository,
                           PlaylistActivityRepository playlistActivityRepository,
                           BlockedSongRepository blockedSongRepository,
                           SongRepository songRepository,
                           UserRepository userRepository,
                           JurisdictionRepository jurisdictionRepository,
                           ScoreUpdateService scoreUpdateService) {
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.playlistFollowRepository = playlistFollowRepository;
        this.playlistVoteRepository = playlistVoteRepository;
        this.playlistActivityRepository = playlistActivityRepository;
        this.blockedSongRepository = blockedSongRepository;
        this.songRepository = songRepository;
        this.userRepository = userRepository;
        this.jurisdictionRepository = jurisdictionRepository;
        this.scoreUpdateService = scoreUpdateService;
    }

    // ========================================================================
    // PERSONAL PLAYLISTS
    // ========================================================================

    public List<PlaylistSummaryResponse> getMyPlaylists(UUID userId) {
        List<Playlist> playlists = playlistRepository.findByOwner(userId);
        return playlists.stream()
                .map(p -> toSummaryResponse(p))
                .collect(Collectors.toList());
    }

    @Transactional
    public PlaylistResponse createPlaylist(UUID userId, CreatePlaylistRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String type = req.getType() != null ? req.getType() : "personal";
        String visibility = req.getVisibility() != null ? req.getVisibility() : "private";

        // Validate type
        if (!List.of("personal", "community", "official").contains(type)) {
            throw new RuntimeException("Invalid playlist type: " + type);
        }

        // Community playlists require a jurisdiction and must be public
        Jurisdiction jurisdiction = null;
        if ("community".equals(type)) {
            if (req.getJurisdictionId() == null) {
                throw new RuntimeException("Community playlists require a jurisdiction");
            }
            jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                    .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
            visibility = "public"; // forced
        }

        // Official playlists are admin-only (checked at controller layer)
        if ("official".equals(type)) {
            visibility = "public"; // forced
        }

        Playlist playlist = Playlist.builder()
                .name(req.getName())
                .user(user)
                .type(type)
                .visibility(visibility)
                .description(req.getDescription())
                .coverImageUrl(req.getCoverImageUrl())
                .jurisdiction(jurisdiction)
                .build();

        playlist = playlistRepository.save(playlist);

        // Log activity for community playlists
        if ("community".equals(type)) {
            logActivity(playlist, user, "playlist_created", null, null);
        }

        // Award points for creating a community playlist (+5)
        scoreUpdateService.onPlaylistCreated(userId, type);

        return toFullResponse(playlist, userId);
    }

    public PlaylistResponse getPlaylistById(UUID playlistId, UUID viewerUserId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        // Visibility check
        if (!playlist.isVisibleTo(viewerUserId)) {
            throw new RuntimeException("Playlist not found");
        }

        return toFullResponse(playlist, viewerUserId);
    }

    @Transactional
    public PlaylistResponse updatePlaylist(UUID playlistId, UUID userId, UpdatePlaylistRequest req) {
        Playlist playlist = getOwnedPlaylist(playlistId, userId);

        if (req.getName() != null && !req.getName().isBlank()) {
            String oldName = playlist.getName();
            playlist.setName(req.getName());

            if (playlist.isCommunity() && !req.getName().equals(oldName)) {
                logActivity(playlist, playlist.getUser(), "playlist_renamed", null,
                        "Renamed from \"" + oldName + "\"");
            }
        }
        if (req.getVisibility() != null) {
            // Community and official playlists stay public
            if (!playlist.isCommunity() && !playlist.isOfficial()) {
                playlist.setVisibility(req.getVisibility());
            }
        }
        if (req.getDescription() != null) {
            playlist.setDescription(req.getDescription());
        }
        if (req.getCoverImageUrl() != null) {
            playlist.setCoverImageUrl(req.getCoverImageUrl());
        }

        playlist = playlistRepository.save(playlist);
        return toFullResponse(playlist, userId);
    }

    @Transactional
    public void deletePlaylist(UUID playlistId, UUID userId) {
        Playlist playlist = getOwnedPlaylist(playlistId, userId);
        playlistRepository.softDelete(playlistId, LocalDateTime.now());
    }

    @Transactional
    public PlaylistResponse addTrack(UUID playlistId, UUID userId, UUID songId) {
        Playlist playlist = getOwnedPlaylist(playlistId, userId);

        // For community playlists, use suggestSong instead
        if (playlist.isCommunity() && !playlist.isOwner(userId)) {
            throw new RuntimeException("Use the suggest endpoint for community playlists");
        }

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found"));

        // Check duplicate
        if (playlistTrackRepository.existsByPlaylist_PlaylistIdAndSong_SongId(playlistId, songId)) {
            throw new RuntimeException("Song already in playlist");
        }

        // Check max songs
        int currentCount = playlistTrackRepository.countActiveByPlaylist(playlistId);
        if (currentCount >= playlist.getMaxSongs()) {
            throw new RuntimeException("Playlist is full (" + playlist.getMaxSongs() + " songs max)");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        PlaylistTrack track = PlaylistTrack.builder()
                .playlist(playlist)
                .song(song)
                .position(currentCount)
                .addedBy(user)
                .status("active")
                .build();

        playlistTrackRepository.save(track);
        playlistRepository.updateSongCount(playlistId, 1);

        return getPlaylistById(playlistId, userId);
    }

    @Transactional
    public PlaylistResponse removeTrack(UUID playlistId, UUID userId, UUID itemId) {
        Playlist playlist = getOwnedPlaylist(playlistId, userId);

        PlaylistTrack track = playlistTrackRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Track not found"));

        if (!track.getPlaylist().getPlaylistId().equals(playlistId)) {
            throw new RuntimeException("Track does not belong to this playlist");
        }

        playlistTrackRepository.delete(track);
        playlistRepository.updateSongCount(playlistId, -1);

        // Reindex positions
        reindexPositions(playlistId);

        return getPlaylistById(playlistId, userId);
    }

    @Transactional
    public PlaylistResponse reorderTracks(UUID playlistId, UUID userId, List<UUID> orderedItemIds) {
        Playlist playlist = getOwnedPlaylist(playlistId, userId);

        List<PlaylistTrack> activeTracks = playlistTrackRepository.findActiveByPlaylist(playlistId);

        // Build lookup map
        Map<UUID, PlaylistTrack> map = new HashMap<>();
        for (PlaylistTrack pt : activeTracks) {
            map.put(pt.getPlaylistItemId(), pt);
        }

        // Validate all existing IDs are present in input
        if (orderedItemIds.size() != activeTracks.size()) {
            throw new RuntimeException("Ordered list must contain all active track IDs");
        }

        int pos = 0;
        for (UUID itemId : orderedItemIds) {
            PlaylistTrack pt = map.get(itemId);
            if (pt == null) {
                throw new RuntimeException("Track ID not found in playlist: " + itemId);
            }
            pt.setPosition(pos++);
            playlistTrackRepository.save(pt);
        }

        return getPlaylistById(playlistId, userId);
    }

    // ========================================================================
    // COMMUNITY PLAYLISTS
    // ========================================================================

    @Transactional
    public TrackResponse suggestSong(UUID playlistId, UUID userId, UUID songId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        if (!playlist.isCommunity()) {
            throw new RuntimeException("Song suggestions are only for community playlists");
        }

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found"));

        // Check duplicate
        if (playlistTrackRepository.existsByPlaylist_PlaylistIdAndSong_SongId(playlistId, songId)) {
            throw new RuntimeException("Song already in or suggested for this playlist");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Determine next position (after all active tracks)
        int nextPosition = playlistTrackRepository.countActiveByPlaylist(playlistId);

        PlaylistTrack track = PlaylistTrack.builder()
                .playlist(playlist)
                .song(song)
                .position(nextPosition)
                .addedBy(user)
                .status("pending")
                .build();

        track = playlistTrackRepository.save(track);

        logActivity(playlist, user, "song_added", song.getSongId(),
                "Suggested: " + song.getTitle());

        return toTrackResponse(track);
    }

    @Transactional
    public TrackResponse voteOnSuggestion(UUID itemId, UUID userId, String voteType) {
        if (!List.of("up", "down").contains(voteType)) {
            throw new RuntimeException("Vote type must be 'up' or 'down'");
        }

        PlaylistTrack track = playlistTrackRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Track not found"));

        if (!track.getPlaylist().isCommunity()) {
            throw new RuntimeException("Voting is only for community playlists");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check for existing vote
        Optional<PlaylistVote> existingVote =
                playlistVoteRepository.findByPlaylistItem_PlaylistItemIdAndUser_UserId(itemId, userId);

        if (existingVote.isPresent()) {
            PlaylistVote vote = existingVote.get();
            if (vote.getVoteType().equals(voteType)) {
                throw new RuntimeException("You already voted " + voteType + " on this track");
            }
            // Switching vote direction
            vote.setVoteType(voteType);
            playlistVoteRepository.save(vote);
        } else {
            PlaylistVote vote = PlaylistVote.builder()
                    .playlistItem(track)
                    .user(user)
                    .voteType(voteType)
                    .build();
            playlistVoteRepository.save(vote);
        }

        // Award +1 to the voter for participating in community curation
        scoreUpdateService.onPlaylistVoteCast(userId);

        // Recount votes
        int upvotes = playlistVoteRepository.countUpvotes(itemId);
        int downvotes = playlistVoteRepository.countDownvotes(itemId);
        track.setUpvotes(upvotes);
        track.setDownvotes(downvotes);

        // Process thresholds
        int netVotes = upvotes - downvotes;
        if (track.isPending() && netVotes >= APPROVE_THRESHOLD) {
            track.setStatus("active");
            playlistRepository.updateSongCount(track.getPlaylist().getPlaylistId(), 1);
            logActivity(track.getPlaylist(), user, "song_approved", track.getSong().getSongId(),
                    "Approved with " + netVotes + " net votes");

            // Award +2 to the user who originally suggested the song
            if (track.getAddedBy() != null) {
                scoreUpdateService.onSongSuggestionApproved(track.getAddedBy().getUserId());
            }
        } else if (netVotes <= REJECT_THRESHOLD) {
            track.setStatus("removed");
            logActivity(track.getPlaylist(), user, "song_rejected", track.getSong().getSongId(),
                    "Rejected with " + netVotes + " net votes");
        }

        // Log the vote itself
        String actionType = "up".equals(voteType) ? "song_voted_up" : "song_voted_down";
        logActivity(track.getPlaylist(), user, actionType, track.getSong().getSongId(), null);

        track = playlistTrackRepository.save(track);
        return toTrackResponse(track);
    }

    @Transactional
    public void curatorRemoveSong(UUID playlistId, UUID curatorId, UUID itemId, String reason) {
        Playlist playlist = getOwnedPlaylist(playlistId, curatorId);

        if (!playlist.isCommunity()) {
            throw new RuntimeException("Curator removal is only for community playlists");
        }

        PlaylistTrack track = playlistTrackRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Track not found"));

        if (!track.getPlaylist().getPlaylistId().equals(playlistId)) {
            throw new RuntimeException("Track does not belong to this playlist");
        }

        User curator = userRepository.findById(curatorId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean wasActive = track.isActive();
        track.setStatus("removed");
        playlistTrackRepository.save(track);

        if (wasActive) {
            playlistRepository.updateSongCount(playlistId, -1);
        }

        logActivity(playlist, curator, "curator_removed", track.getSong().getSongId(),
                reason != null ? "Reason: " + reason : null);
    }

    public List<TrackResponse> getPendingSuggestions(UUID playlistId, UUID viewerUserId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        if (!playlist.isVisibleTo(viewerUserId)) {
            throw new RuntimeException("Playlist not found");
        }

        return playlistTrackRepository.findPendingByPlaylist(playlistId).stream()
                .map(this::toTrackResponse)
                .collect(Collectors.toList());
    }

    public List<ActivityResponse> getPlaylistActivity(UUID playlistId, UUID viewerUserId,
                                                       int page, int size) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        if (!playlist.isVisibleTo(viewerUserId)) {
            throw new RuntimeException("Playlist not found");
        }

        Page<PlaylistActivity> activities = playlistActivityRepository
                .findByPlaylist_PlaylistId(playlistId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        return activities.stream()
                .map(this::toActivityResponse)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // FOLLOWING
    // ========================================================================

    @Transactional
    public void followPlaylist(UUID userId, UUID playlistId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        // Can't follow private playlists
        if ("private".equals(playlist.getVisibility())) {
            throw new RuntimeException("Cannot follow a private playlist");
        }

        // Can't follow your own playlist
        if (playlist.isOwner(userId)) {
            throw new RuntimeException("Cannot follow your own playlist");
        }

        if (playlistFollowRepository.existsByPlaylist_PlaylistIdAndUser_UserId(playlistId, userId)) {
            throw new RuntimeException("Already following this playlist");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        PlaylistFollow follow = PlaylistFollow.builder()
                .playlist(playlist)
                .user(user)
                .build();

        playlistFollowRepository.save(follow);
        playlistRepository.updateFollowerCount(playlistId, 1);

        // Award creator +10 points if this follow brings them to the 10-follower milestone
        int newFollowerCount = playlist.getFollowerCount() + 1;
        if (playlist.getUser() != null) {
            scoreUpdateService.onPlaylistFollowerMilestone(
                    playlist.getUser().getUserId(),
                    newFollowerCount
            );
        }
    }

    @Transactional
    public void unfollowPlaylist(UUID userId, UUID playlistId) {
        if (!playlistFollowRepository.existsByPlaylist_PlaylistIdAndUser_UserId(playlistId, userId)) {
            throw new RuntimeException("Not following this playlist");
        }

        playlistFollowRepository.deleteByPlaylist_PlaylistIdAndUser_UserId(playlistId, userId);
        playlistRepository.updateFollowerCount(playlistId, -1);
    }

    public List<PlaylistSummaryResponse> getFollowedPlaylists(UUID userId) {
        return playlistFollowRepository.findFollowedPlaylists(userId).stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // DISCOVERY
    // ========================================================================

    public List<PlaylistSummaryResponse> discoverPlaylists(UUID jurisdictionId) {
        List<Playlist> playlists;
        if (jurisdictionId != null) {
            playlists = playlistRepository.findPublicByJurisdiction(jurisdictionId);
        } else {
            playlists = playlistRepository.findAllPublic();
        }
        return playlists.stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }

    public List<PlaylistSummaryResponse> getCommunityPlaylists(UUID jurisdictionId) {
        return playlistRepository.findCommunityByJurisdiction(jurisdictionId).stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }

    public List<PlaylistSummaryResponse> getOfficialPlaylists() {
        return playlistRepository.findOfficialPlaylists().stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }

    public List<PlaylistSummaryResponse> searchPlaylists(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        return playlistRepository.searchPublicPlaylists(query.trim()).stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // BLOCKED SONGS
    // ========================================================================

    @Transactional
    public void blockSong(UUID userId, UUID songId) {
        if (blockedSongRepository.existsByUser_UserIdAndSong_SongId(userId, songId)) {
            throw new RuntimeException("Song is already blocked");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found"));

        BlockedSong blocked = BlockedSong.builder()
                .user(user)
                .song(song)
                .build();

        blockedSongRepository.save(blocked);
    }

    @Transactional
    public void unblockSong(UUID userId, UUID songId) {
        if (!blockedSongRepository.existsByUser_UserIdAndSong_SongId(userId, songId)) {
            throw new RuntimeException("Song is not blocked");
        }
        blockedSongRepository.deleteByUser_UserIdAndSong_SongId(userId, songId);
    }

    public List<BlockedSongResponse> getBlockedSongs(UUID userId) {
        return blockedSongRepository.findByUser_UserId(userId).stream()
                .map(bs -> BlockedSongResponse.builder()
                        .songId(bs.getSong().getSongId())
                        .title(bs.getSong().getTitle())
                        .artistName(bs.getSong().getArtist() != null
                                ? bs.getSong().getArtist().getUsername() : "Unknown")
                        .artworkUrl(bs.getSong().getArtworkUrl())
                        .blockedAt(bs.getBlockedAt())
                        .build())
                .collect(Collectors.toList());
    }

    public List<UUID> getBlockedSongIds(UUID userId) {
        return blockedSongRepository.findBlockedSongIdsByUserId(userId);
    }

    // ========================================================================
    // LEGACY COMPATIBILITY
    // ========================================================================

    /**
     * Legacy method for backward compatibility with existing controller.
     * Prefer getMyPlaylists(UUID) for new code.
     */
    public List<Playlist> getPlaylistsForUser(User user) {
        return playlistRepository.findByUser(user);
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private Playlist getOwnedPlaylist(UUID playlistId, UUID userId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("Playlist not found"));

        if (!playlist.isOwner(userId)) {
            throw new RuntimeException("Unauthorized: you do not own this playlist");
        }

        return playlist;
    }

    private void logActivity(Playlist playlist, User user, String actionType,
                             UUID targetSongId, String details) {
        PlaylistActivity activity = PlaylistActivity.builder()
                .playlist(playlist)
                .user(user)
                .actionType(actionType)
                .targetSongId(targetSongId)
                .details(details)
                .build();
        playlistActivityRepository.save(activity);
    }

    private void reindexPositions(UUID playlistId) {
        List<PlaylistTrack> tracks = playlistTrackRepository.findActiveByPlaylist(playlistId);
        for (int i = 0; i < tracks.size(); i++) {
            tracks.get(i).setPosition(i);
            playlistTrackRepository.save(tracks.get(i));
        }
    }

    // ========================================================================
    // DTO MAPPING
    // ========================================================================

    private PlaylistResponse toFullResponse(Playlist p, UUID viewerUserId) {
        boolean isFollowing = viewerUserId != null &&
                playlistFollowRepository.existsByPlaylist_PlaylistIdAndUser_UserId(
                        p.getPlaylistId(), viewerUserId);

        boolean isOwner = viewerUserId != null && p.isOwner(viewerUserId);

        List<TrackResponse> tracks = p.getItems() != null
                ? p.getItems().stream()
                    .filter(t -> "active".equals(t.getStatus()))
                    .map(this::toTrackResponse)
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return PlaylistResponse.builder()
                .playlistId(p.getPlaylistId())
                .name(p.getName())
                .type(p.getType())
                .visibility(p.getVisibility())
                .description(p.getDescription())
                .coverImageUrl(p.getCoverImageUrl())
                .jurisdictionId(p.getJurisdiction() != null
                        ? p.getJurisdiction().getJurisdictionId() : null)
                .jurisdictionName(p.getJurisdiction() != null
                        ? p.getJurisdiction().getName() : null)
                .creatorId(p.getUser() != null ? p.getUser().getUserId() : null)
                .creatorName(p.getUser() != null ? p.getUser().getUsername() : null)
                .creatorPhotoUrl(p.getUser() != null ? p.getUser().getPhotoUrl() : null)
                .songCount(p.getSongCount())
                .followerCount(p.getFollowerCount())
                .isFollowing(isFollowing)
                .isOwner(isOwner)
                .tracks(tracks)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private PlaylistSummaryResponse toSummaryResponse(Playlist p) {
        // Grab first 4 artwork URLs for mosaic fallback
        List<String> artworks = Collections.emptyList();
        if (p.getItems() != null && !p.getItems().isEmpty()) {
            artworks = p.getItems().stream()
                    .filter(t -> "active".equals(t.getStatus()))
                    .limit(4)
                    .map(t -> t.getSong() != null ? t.getSong().getArtworkUrl() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        return PlaylistSummaryResponse.builder()
                .playlistId(p.getPlaylistId())
                .name(p.getName())
                .type(p.getType())
                .visibility(p.getVisibility())
                .songCount(p.getSongCount())
                .followerCount(p.getFollowerCount())
                .coverImageUrl(p.getCoverImageUrl())
                .creatorName(p.getUser() != null ? p.getUser().getUsername() : null)
                .creatorId(p.getUser() != null ? p.getUser().getUserId() : null)
                .firstFourArtworks(artworks)
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private TrackResponse toTrackResponse(PlaylistTrack t) {
        Song song = t.getSong();
        return TrackResponse.builder()
                .playlistItemId(t.getPlaylistItemId())
                .songId(song != null ? song.getSongId() : null)
                .title(song != null ? song.getTitle() : null)
                .artistName(song != null && song.getArtist() != null
                        ? song.getArtist().getUsername() : null)
                .artistId(song != null && song.getArtist() != null
                        ? song.getArtist().getUserId() : null)
                .artworkUrl(song != null ? song.getArtworkUrl() : null)
                .fileUrl(song != null ? song.getFileUrl() : null)
                .duration(song != null ? song.getDuration() : null)
                .position(t.getPosition())
                .addedAt(t.getAddedAt())
                .addedByUsername(t.getAddedBy() != null ? t.getAddedBy().getUsername() : null)
                .upvotes(t.getUpvotes())
                .downvotes(t.getDownvotes())
                .status(t.getStatus())
                .build();
    }

    private ActivityResponse toActivityResponse(PlaylistActivity a) {
        // Look up song title if we have a target song ID
        String songTitle = null;
        if (a.getTargetSongId() != null) {
            songTitle = songRepository.findById(a.getTargetSongId())
                    .map(Song::getTitle)
                    .orElse(null);
        }

        return ActivityResponse.builder()
                .activityId(a.getActivityId())
                .username(a.getUser() != null ? a.getUser().getUsername() : null)
                .userPhotoUrl(a.getUser() != null ? a.getUser().getPhotoUrl() : null)
                .actionType(a.getActionType())
                .songTitle(songTitle)
                .songId(a.getTargetSongId())
                .details(a.getDetails())
                .createdAt(a.getCreatedAt())
                .build();
    }
}