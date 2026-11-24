package com.unis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unis.entity.Release;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.ReleaseRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Releases: albums, EPs, mixtapes and singles.
 *
 * A release groups songs the artist already has. It never changes a song's
 * plays, likes, votes or races. A song is on at most one release; removing a
 * release puts its songs back to being singles.
 */
@Service
@Slf4j
public class ReleaseService {

    public static final int MAX_TRACKS = 40;
    private static final long MAX_COVER_BYTES = 10L * 1024 * 1024;

    public record ReleaseView(UUID releaseId, String title, String type, String coverUrl, String description,
                              LocalDateTime releasedAt, UUID artistId, String artistName, String artistPhotoUrl,
                              int trackCount, long durationMs) {}

    public record ReleaseDetail(ReleaseView release, List<Song> tracks) {}

    private final ReleaseRepository releases;
    private final SongRepository songs;
    private final UserRepository users;
    private final FileStorageService fileStorageService;
    private final ArtistTeamService teamService;
    private final ReleaseAlertService alertService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReleaseService(ReleaseRepository releases, SongRepository songs, UserRepository users,
                          FileStorageService fileStorageService, ArtistTeamService teamService,
                          ReleaseAlertService alertService) {
        this.releases = releases;
        this.songs = songs;
        this.users = users;
        this.fileStorageService = fileStorageService;
        this.teamService = teamService;
        this.alertService = alertService;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReleaseView> forArtist(UUID artistId) {
        User artist = users.findById(artistId).orElse(null);
        if (artist == null || artist.isDeleted()) return List.of();
        List<ReleaseView> out = new ArrayList<>();
        for (Release r : releases.findLiveByArtist(artistId)) {
            List<Song> tracks = songs.findLiveByReleaseId(r.getReleaseId());
            if (tracks.isEmpty()) continue;   // every song removed: nothing to show
            out.add(view(r, artist, tracks));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ReleaseDetail get(UUID releaseId) {
        Release r = releases.findLive(releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found."));
        User artist = users.findById(r.getArtistId()).orElse(null);
        if (artist == null || artist.isDeleted()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found.");
        List<Song> tracks = songs.findLiveByReleaseId(releaseId);
        return new ReleaseDetail(view(r, artist, tracks), tracks);
    }

    // ── Writing (the artist, or a manager acting for them) ──────────────────

    @Transactional
    @CacheEvict(value = {"songs", "artists"}, allEntries = true)
    public ReleaseDetail create(UUID artistId, String json, MultipartFile cover) {
        User artist = requireArtist(artistId);
        Input in = parse(json, true);
        List<Song> tracks = claimSongs(artistId, in.songIds, null);

        LocalDateTime now = LocalDateTime.now();
        Release r = Release.builder()
                .artistId(artistId).title(in.title).releaseType(in.type)
                .description(in.description)
                .coverUrl(storeCover(cover))
                .releasedAt(now).createdAt(now).updatedAt(now)
                .build();
        r = releases.save(r);
        placeSongs(r.getReleaseId(), tracks);

        teamService.logIfActing(artistId, "published the " + typeWord(in.type) + " " + in.title);
        log.info("[Release] created id={} artist={} type={} tracks={} notify={}", r.getReleaseId(), artistId, in.type, tracks.size(), in.notifyFollowers);
        if (in.notifyFollowers) alertAfterCommit(artistId, r.getReleaseId(), in.type, tracks, in.followerNote);
        return new ReleaseDetail(view(r, artist, tracks), tracks);
    }

    @Transactional
    @CacheEvict(value = {"songs", "artists"}, allEntries = true)
    public ReleaseDetail update(UUID releaseId, UUID artistId, String json, MultipartFile cover) {
        User artist = requireArtist(artistId);
        Release r = ownRelease(releaseId, artistId);
        Input in = parse(json, false);

        if (in.title != null) r.setTitle(in.title);
        if (in.type != null) r.setReleaseType(in.type);
        if (in.descriptionSet) r.setDescription(in.description);
        String newCover = storeCover(cover);
        if (newCover != null) r.setCoverUrl(newCover);
        r.setUpdatedAt(LocalDateTime.now());

        List<Song> tracks;
        if (in.songIds != null) {
            tracks = claimSongs(artistId, in.songIds, releaseId);
            Set<UUID> keep = new HashSet<>(in.songIds);
            for (Song s : songs.findAllByReleaseId(releaseId)) {
                if (!keep.contains(s.getSongId())) {
                    s.setReleaseId(null);
                    s.setTrackNumber(null);
                    songs.save(s);
                }
            }
            placeSongs(releaseId, tracks);
        } else {
            tracks = songs.findLiveByReleaseId(releaseId);
        }
        r = releases.save(r);

        teamService.logIfActing(artistId, "edited the " + typeWord(r.getReleaseType()) + " " + r.getTitle());
        return new ReleaseDetail(view(r, artist, tracks), tracks);
    }

    @Transactional
    @CacheEvict(value = {"songs", "artists"}, allEntries = true)
    public void delete(UUID releaseId, UUID artistId) {
        Release r = ownRelease(releaseId, artistId);
        for (Song s : songs.findAllByReleaseId(releaseId)) {
            s.setReleaseId(null);
            s.setTrackNumber(null);
            songs.save(s);
        }
        r.setDeletedAt(LocalDateTime.now());
        releases.save(r);
        teamService.logIfActing(artistId, "removed the " + typeWord(r.getReleaseType()) + " " + r.getTitle());
        log.info("[Release] removed id={} artist={}", releaseId, artistId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record Input(String title, Release.Type type, String description, boolean descriptionSet, List<UUID> songIds,
                         boolean notifyFollowers, String followerNote) {}

    /**
     * Tells followers once the release is saved for good. Runs after the
     * commit, in its own transaction, so an alert problem never undoes the
     * release; it's only logged.
     */
    private void alertAfterCommit(UUID artistId, UUID releaseId, Release.Type type, List<Song> tracks, String note) {
        List<UUID> trackIds = tracks.stream().map(Song::getSongId).toList();
        Runnable send = () -> {
            try {
                alertService.releasePublished(artistId, releaseId, type, trackIds, note);
            } catch (Exception e) {
                log.error("[Release] follower alert failed release={} err={}", releaseId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private Input parse(String json, boolean creating) {
        JsonNode n;
        try {
            n = objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw bad("The release details couldn't be read.");
        }
        String title = n.hasNonNull("title") ? n.get("title").asText().trim() : null;
        if (title != null && title.isEmpty()) title = null;
        if (creating && title == null) throw bad("Give the release a title.");
        if (title != null && title.length() > 120) throw bad("Titles can be up to 120 characters.");

        Release.Type type = null;
        if (n.hasNonNull("type")) {
            try {
                type = Release.Type.valueOf(n.get("type").asText().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw bad("Choose Album, EP, Mixtape or Single.");
            }
        }
        if (creating && type == null) throw bad("Choose Album, EP, Mixtape or Single.");

        boolean descSet = n.has("description");
        String desc = descSet && !n.get("description").isNull() ? n.get("description").asText().trim() : null;
        if (desc != null && desc.isEmpty()) desc = null;
        if (desc != null && desc.length() > 2000) throw bad("The description can be up to 2,000 characters.");

        List<UUID> ids = null;
        if (n.has("songIds") && n.get("songIds").isArray()) {
            LinkedHashSet<UUID> set = new LinkedHashSet<>();
            for (JsonNode x : n.get("songIds")) {
                try {
                    set.add(UUID.fromString(x.asText()));
                } catch (IllegalArgumentException e) {
                    throw bad("One of the songs couldn't be found.");
                }
            }
            ids = new ArrayList<>(set);
        }
        if (creating && (ids == null || ids.isEmpty())) throw bad("Add at least one song.");
        if (ids != null && ids.isEmpty()) throw bad("A release needs at least one song.");
        if (ids != null && ids.size() > MAX_TRACKS) throw bad("A release can have up to " + MAX_TRACKS + " songs.");

        // Follower alert (creating only): on unless the artist switched it off.
        boolean notify = !n.has("notifyFollowers") || n.get("notifyFollowers").isNull() || n.get("notifyFollowers").asBoolean(true);
        String note = n.hasNonNull("followerNote") ? ReleaseAlertService.cleanNote(n.get("followerNote").asText()) : null;
        return new Input(title, type, desc, descSet, ids, creating && notify, creating ? note : null);
    }

    /** The songs, in order, after checking they're this artist's, live, and free (or already on this release). */
    private List<Song> claimSongs(UUID artistId, List<UUID> ids, UUID thisRelease) {
        Map<UUID, Song> found = new java.util.HashMap<>();
        songs.findAllById(ids).forEach(s -> found.put(s.getSongId(), s));
        List<Song> out = new ArrayList<>();
        for (UUID id : ids) {
            Song s = found.get(id);
            if (s == null || s.getDeletedAt() != null || s.getArtist() == null || !artistId.equals(s.getArtist().getUserId())) {
                throw bad("One of the songs couldn't be found.");
            }
            if (s.getReleaseId() != null && !s.getReleaseId().equals(thisRelease)) {
                String other = releases.findLive(s.getReleaseId()).map(Release::getTitle).orElse(null);
                if (other != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "“" + s.getTitle() + "” is already on " + other + ". Take it off there first.");
                }
            }
            out.add(s);
        }
        return out;
    }

    private void placeSongs(UUID releaseId, List<Song> tracks) {
        int n = 1;
        for (Song s : tracks) {
            s.setReleaseId(releaseId);
            s.setTrackNumber(n++);
            songs.save(s);
        }
    }

    private String storeCover(MultipartFile cover) {
        if (cover == null || cover.isEmpty()) return null;
        UploadValidator.require(cover, UploadValidator.Kind.IMAGE, MAX_COVER_BYTES);
        return fileStorageService.storeFile(cover);
    }

    private Release ownRelease(UUID releaseId, UUID artistId) {
        Release r = releases.findLive(releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found."));
        if (!r.getArtistId().equals(artistId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That release isn't yours.");
        }
        return r;
    }

    private User requireArtist(UUID artistId) {
        User u = users.findById(artistId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (u.isDeleted() || u.getRole() != User.Role.artist) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only artists can publish releases.");
        }
        return u;
    }

    private ReleaseView view(Release r, User artist, List<Song> tracks) {
        long duration = 0;
        for (Song s : tracks) duration += s.getDuration() == null ? 0 : s.getDuration();
        String cover = r.getCoverUrl();
        if (cover == null) {
            cover = tracks.stream().map(Song::getArtworkUrl).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        return new ReleaseView(r.getReleaseId(), r.getTitle(), r.getReleaseType().name(), cover, r.getDescription(),
                r.getReleasedAt(), artist.getUserId(), artist.getUsername(), artist.getPhotoUrl(),
                tracks.size(), duration);
    }

    private static String typeWord(Release.Type t) {
        return switch (t) {
            case ALBUM -> "album";
            case EP -> "EP";
            case MIXTAPE -> "mixtape";
            case SINGLE -> "single";
        };
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
