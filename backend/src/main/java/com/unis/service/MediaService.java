package com.unis.service;

import com.fasterxml.jackson.databind.ObjectMapper; 
import com.unis.dto.SongUploadRequest;
import com.unis.dto.SongDiscoveryRequest;
import com.unis.entity.Mood;
import com.unis.entity.Subgenre;
import com.unis.repository.MoodRepository;
import com.unis.repository.SubgenreRepository;
import com.unis.dto.VideoUploadRequest;
import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.entity.SongPlay;
import com.unis.entity.VideoPlay;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.util.SecurityUtils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.JurisdictionRepository;


import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Transactional
public class MediaService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MediaService.class);

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private SongPlayRepository songPlayRepository;

    @Autowired
    private VideoPlayRepository videoPlayRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired 
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private SubgenreRepository subgenreRepository;

    @Autowired
    private MoodRepository moodRepository;

    @Autowired
    private CacheManager cacheManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Add song - EVICTS cache because new content affects trending/artist lists
    // C1 + C6 FIX: artistId now comes from JWT, not from client JSON
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    // ★ Authenticated path (dashboard uploads) — artist comes from the JWT
    public Song addSong(String songJson, MultipartFile file, MultipartFile artwork) {
        return addSongForArtist(SecurityUtils.getAuthenticatedUserId(), songJson, file, artwork);
    }

    // ★ Shared core — artistId passed in, so signup (no JWT yet) can reuse every line below
    public Song addSongForArtist(UUID authenticatedUserId, String songJson, MultipartFile file, MultipartFile artwork) {
        try {
            SongUploadRequest req = objectMapper.readValue(songJson, SongUploadRequest.class);
            
            // Guards 
            if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
                throw new IllegalArgumentException("Title is required");
            }
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Audio file is required");
            }
            if (!Boolean.TRUE.equals(req.getRightsConfirmed())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, RIGHTS_REFUSED_SONG);
            }

            // Resolve artist using authenticated user
            User artist = userRepository.findById(authenticatedUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + authenticatedUserId));

            // One upload per day per artist — enforced server-side.
            // Clean companion versions are exempt (explicit + clean = one upload).
            if (!Boolean.TRUE.equals(req.getIsCleanVersion())) {
                long uploadsToday = songRepository.countByArtistSince(
                        authenticatedUserId, LocalDate.now().atStartOfDay());
                if (uploadsToday >= 1) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "You can upload one song per day. Try again tomorrow.");
                }
            }

            // Resolve genre (optional)
            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId()).orElse(null);
            }

            // Download settings — also checked before any file is stored. Same
            // rules as DownloadService.updateDownloadSettings.
            String downloadPolicy = req.getDownloadPolicy() == null || req.getDownloadPolicy().isBlank()
                    ? "free" : req.getDownloadPolicy().trim();
            if (!java.util.List.of("free", "paid", "unavailable").contains(downloadPolicy)) {
                throw new IllegalArgumentException("Download policy must be free, paid, or unavailable");
            }
            Integer downloadPrice = null;
            if ("paid".equals(downloadPolicy)) {
                downloadPrice = req.getDownloadPrice();
                if (downloadPrice == null || downloadPrice < PlatformFees.MIN_SONG_PRICE_CENTS) {
                    throw new IllegalArgumentException("Paid downloads require a minimum price of $"
                            + String.format("%.2f", PlatformFees.MIN_SONG_PRICE_CENTS / 100.0));
                }
            }

            // Discovery tags — checked BEFORE any file is stored, so a rejected
            // upload never leaves orphaned audio or artwork in R2.
            Discovery discovery = resolveDiscovery(genre,
                    req.getPrimarySubgenreId(), req.getSecondarySubgenreId(), req.getSubgenreWriteIn(),
                    req.getPrimaryMoodId(), req.getSecondaryMoodId(), req.getLanguage());

            // Resolve jurisdiction
            Jurisdiction jurisdiction = null;
            if (req.getJurisdictionId() != null) {
                jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                        .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + req.getJurisdictionId()));
            } else if (artist.getJurisdiction() != null) {
                jurisdiction = artist.getJurisdiction();
            } else {
                jurisdiction = jurisdictionRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .orElseThrow(() -> new IllegalArgumentException("Default jurisdiction not found"));
            }

            // File storage
            String fileUrl = fileStorageService.storeFile(file);
            String artworkUrl = null;
            if (artwork != null && !artwork.isEmpty()) {
                artworkUrl = fileStorageService.storeFile(artwork);
            }

            // Duration 
            Integer duration = req.getDuration() != null ? req.getDuration() : computeDuration(file);

            // Build & save 
            Song song = new Song();
            song.setTitle(req.getTitle());
            song.setArtist(artist);
            song.setGenre(genre);
            song.setJurisdiction(jurisdiction);
            song.setDescription(req.getDescription());
            song.setDuration(duration);
            song.setFileUrl(fileUrl);
            song.setArtworkUrl(artworkUrl);
            song.setScore(0);
            song.setLevel("silver");
            song.setCreatedAt(LocalDateTime.now());
            song.setExplicit(req.getExplicit() != null ? req.getExplicit() : false);
            song.setLyrics(req.getLyrics());
            song.setPlaysToday(0);
            song.setLastPlayResetDate(LocalDate.now());
            song.setDownloadPolicy(downloadPolicy);
            song.setDownloadPrice(downloadPrice);
            discovery.applyTo(song);


            // ISRC (optional)
            if (req.getIsrc() != null && !req.getIsrc().isBlank()) {
                String cleanIsrc = req.getIsrc().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                if (cleanIsrc.length() != 12) {
                    throw new IllegalArgumentException("ISRC must be exactly 12 alphanumeric characters");
                }
                song.setIsrc(cleanIsrc);
            }

            Song savedSong = songRepository.save(song);
            recordRightsConfirmed("songs", "song_id", savedSong.getSongId());

            // If this is the artist's first song (no default song set), make it the default
            if (artist.getDefaultSongId() == null) {
                artist.setDefaultSongId(savedSong.getSongId());
                userRepository.save(artist);
            }

            return savedSong;
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid upload data: " + e.getMessage(), e);
        }
    }


    // ========================================================================
    // UPLOAD RIGHTS (Terms 3.6)
    // ========================================================================
    //
    // Until Unis holds licenses from The MLC and the performing rights
    // organizations, it can only carry songs the uploader controls: no
    // publisher or publishing administrator, no SESAC or GMR writer, no
    // covers, no uncleared samples. Every upload path (the upload wizard, the
    // signup debut song, the mobile app) shows a checkbox for this and sends
    // rightsConfirmed: true. The server refuses anything else, so no client can
    // skip it, and keeps the time it was confirmed on the song or video row.

    public static final String RIGHTS_REFUSED_SONG =
            "Check the box confirming you control this song's rights before uploading. Unis can't accept songs "
            + "a publisher or publishing administrator handles, songs with a SESAC or GMR writer, covers, or songs "
            + "with uncleared samples.";
    public static final String RIGHTS_REFUSED_VIDEO =
            "Check the box confirming you control the rights to the music in this video before uploading. Unis "
            + "can't accept music a publisher or publishing administrator handles, music with a SESAC or GMR "
            + "writer, covers, or music with uncleared samples.";

    /**
     * Refuses upload metadata that doesn't confirm the rights. Used where the
     * check must run before anything else happens (the signup debut song, whose
     * single-use token would otherwise be spent). Unreadable JSON is left for
     * the upload itself to report.
     */
    public void requireRightsConfirmed(String json, boolean video) {
        boolean confirmed;
        try {
            confirmed = objectMapper.readTree(json).path("rightsConfirmed").asBoolean(false);
        } catch (IOException e) {
            return;
        }
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, video ? RIGHTS_REFUSED_VIDEO : RIGHTS_REFUSED_SONG);
        }
    }

    /** True once V2026_10l__upload_rights.sql has added the columns; checked again until then. */
    private volatile boolean rightsColumnsReady;

    /**
     * Stamps when the uploader confirmed the rights. If the SQL hasn't been run
     * yet the upload still goes through and this logs an error, because a
     * failed statement here would roll the whole upload back.
     */
    private void recordRightsConfirmed(String table, String idColumn, UUID id) {
        if (!rightsColumnsReady) {
            Integer found = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                            + "AND table_name IN ('songs', 'videos') AND column_name = 'rights_confirmed_at'",
                    Integer.class);
            rightsColumnsReady = found != null && found == 2;
            if (!rightsColumnsReady) {
                log.error("[UploadRights] action=record status=skipped table={} id={} reason=column_missing "
                        + "fix=run backend/sql/V2026_10l__upload_rights.sql", table, id);
                return;
            }
        }
        entityManager.flush();   // the new row must exist before it can be stamped
        jdbcTemplate.update("UPDATE " + table + " SET rights_confirmed_at = NOW() WHERE " + idColumn + " = ?", id);
        log.info("[UploadRights] action=record status=ok table={} id={}", table, id);
    }


    // ========================================================================
    // DISCOVERY LAYER — subgenres, moods, language
    // ========================================================================
    //
    // The rules (the help center's "Genres and moods" section says the same):
    //   • Primary subgenre: required, and it must belong to the song's own
    //     genre. If nothing fits, the artist types a write-in instead; an admin
    //     maps it at the quarterly review (query in the SQL migration file).
    //   • Secondary subgenre: optional, ANY genre (crossover), not the primary.
    //   • Primary mood: required. Secondary mood: optional, not the primary.
    //   • Language: one of LANGUAGES; defaults to English.
    // Genres alone drive voting, awards and charts. None of this touches them.

    /** Language codes a song can carry, in the order the upload form shows them. */
    public static final java.util.Map<String, String> LANGUAGES;
    static {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        m.put("en", "English");
        m.put("es", "Spanish");
        m.put("fr", "French");
        m.put("other", "Other language");
        m.put("instrumental", "Instrumental (no vocals)");
        LANGUAGES = java.util.Collections.unmodifiableMap(m);
    }

    private static final int WRITE_IN_MAX = 60;

    /** Validated tags, ready to copy onto a song. */
    private record Discovery(Subgenre primary, Subgenre secondary, String writeIn,
                             Mood primaryMood, Mood secondaryMood, String language) {
        void applyTo(Song song) {
            song.setPrimarySubgenre(primary);
            song.setSecondarySubgenre(secondary);
            song.setSubgenreWriteIn(writeIn);
            song.setPrimaryMood(primaryMood);
            song.setSecondaryMood(secondaryMood);
            song.setLanguage(language);
        }
    }

    private static ResponseStatusException badTag(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Discovery resolveDiscovery(Genre genre,
                                       UUID primarySubgenreId, UUID secondarySubgenreId, String writeInRaw,
                                       UUID primaryMoodId, UUID secondaryMoodId, String languageRaw) {
        String writeIn = writeInRaw == null ? null : writeInRaw.trim();
        if (writeIn != null && writeIn.isEmpty()) writeIn = null;

        Subgenre primary = null;
        if (primarySubgenreId != null) {
            primary = subgenreRepository.findById(primarySubgenreId)
                    .filter(sg -> Boolean.TRUE.equals(sg.getActive()))
                    .orElseThrow(() -> badTag("That subgenre isn't available. Pick another."));
            if (genre != null && !primary.getGenreId().equals(genre.getGenreId())) {
                throw badTag("Your primary subgenre has to be under this song's genre.");
            }
            writeIn = null;   // a real subgenre wins over a write-in
        } else if (writeIn == null) {
            throw badTag("Choose a subgenre for this song (or choose Other and type one in).");
        } else if (writeIn.length() > WRITE_IN_MAX) {
            throw badTag("Keep the subgenre you type in to " + WRITE_IN_MAX + " characters.");
        }

        Subgenre secondary = null;
        if (secondarySubgenreId != null) {
            if (primary != null && secondarySubgenreId.equals(primary.getSubgenreId())) {
                throw badTag("The second subgenre has to be different from the first.");
            }
            secondary = subgenreRepository.findById(secondarySubgenreId)
                    .filter(sg -> Boolean.TRUE.equals(sg.getActive()))
                    .orElseThrow(() -> badTag("That second subgenre isn't available. Pick another."));
        }

        if (primaryMoodId == null) {
            throw badTag("Choose at least one mood for this song.");
        }
        Mood primaryMood = moodRepository.findById(primaryMoodId)
                .filter(m -> Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> badTag("That mood isn't available. Pick another."));

        Mood secondaryMood = null;
        if (secondaryMoodId != null) {
            if (secondaryMoodId.equals(primaryMoodId)) {
                throw badTag("The second mood has to be different from the first.");
            }
            secondaryMood = moodRepository.findById(secondaryMoodId)
                    .filter(m -> Boolean.TRUE.equals(m.getActive()))
                    .orElseThrow(() -> badTag("That second mood isn't available. Pick another."));
        }

        String language = languageRaw == null || languageRaw.isBlank() ? "en" : languageRaw.trim().toLowerCase();
        if (!LANGUAGES.containsKey(language)) {
            throw badTag("Pick a language from the list.");
        }

        return new Discovery(primary, secondary, writeIn, primaryMood, secondaryMood, language);
    }

    /**
     * Tag or re-tag a song. Only its artist or an admin. Used by the edit-song
     * wizard, which is how songs uploaded before this existed get their tags.
     */
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    @Transactional
    public Song updateDiscovery(UUID songId, UUID requesterId, boolean requesterIsAdmin, SongDiscoveryRequest req) {
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
        boolean isOwner = song.getArtist() != null && song.getArtist().getUserId().equals(requesterId);
        if (!isOwner && !requesterIsAdmin) {
            throw new NotOwnerException("You can only edit your own songs.");
        }
        if (song.isRemoved()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This song was removed and can't be edited.");
        }

        Discovery discovery = resolveDiscovery(song.getGenre(),
                req.getPrimarySubgenreId(), req.getSecondarySubgenreId(), req.getSubgenreWriteIn(),
                req.getPrimaryMoodId(), req.getSecondaryMoodId(), req.getLanguage());
        discovery.applyTo(song);

        // The clean version is the same song, so it carries the same tags.
        if (song.getCleanVersionId() != null) {
            songRepository.findById(song.getCleanVersionId()).ifPresent(clean -> {
                discovery.applyTo(clean);
                songRepository.save(clean);
            });
        }
        return songRepository.save(song);
    }

    // NOT CACHED - private helper method
    private Integer computeDuration(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("File is null or empty, returning fallback duration");
            return 180000;
        }
        
        try (InputStream is = file.getInputStream()) {
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext context = new ParseContext();
            
            parser.parse(is, handler, metadata, context);
            
            log.debug("=== Audio Metadata for: {} ===", file.getOriginalFilename());
            for (String name : metadata.names()) {
                log.debug("{}: {}", name, metadata.get(name));
            }
            
            String durStr = metadata.get("xmpDM:duration");
            if (durStr == null) durStr = metadata.get("duration");
            if (durStr == null) durStr = metadata.get("Content-Duration");
            if (durStr == null) durStr = metadata.get("xmpDM:audioSampleRate");
            
            if (durStr != null && !durStr.isEmpty()) {
                try {
                    double seconds = Double.parseDouble(durStr);
                    int milliseconds = (int) (seconds * 1000);
                    log.debug("Parsed duration: {}ms ({}s)", milliseconds, seconds);
                    return milliseconds;
                } catch (NumberFormatException e) {
                    log.warn("Could not parse duration value: {}", durStr);
                }
            } else {
                log.warn("No duration metadata found in file");
            }
            
        } catch (Exception e) {
            log.error("Duration parse failed for {}", file.getOriginalFilename(), e);
        }
        
        log.warn("Returning fallback duration of 180000ms (3 min)");
        return 180000;
    }

    // NOT CACHED - Video methods (you said video is coming later)
    public Video addVideo(String videoJson, MultipartFile file, MultipartFile artwork) {
        try {
            VideoUploadRequest req = objectMapper.readValue(videoJson, VideoUploadRequest.class);

            // The uploader is whoever is signed in. The artistId in the request
            // used to be trusted, so any account could post a video under any
            // artist's name. It's now ignored, and only artists can upload.
            UUID uploaderId = SecurityUtils.getAuthenticatedUserId();
            User artist = userRepository.findById(uploaderId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
            if (artist.getRole() != User.Role.artist || artist.getDeletedAt() != null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Only artists can upload videos.");
            }
            if (!Boolean.TRUE.equals(req.getRightsConfirmed())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, RIGHTS_REFUSED_VIDEO);
            }

            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId())
                        .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + req.getGenreId()));
            }

            Jurisdiction jurisdiction = null;
            if (req.getJurisdictionId() != null) {
                jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                        .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + req.getJurisdictionId()));
            } else if (artist.getJurisdiction() != null) {
                jurisdiction = artist.getJurisdiction();
            } else {
                jurisdiction = jurisdictionRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .orElseThrow(() -> new IllegalArgumentException("Default jurisdiction not found"));
            }

            String videoUrl = fileStorageService.storeFile(file);

            String artworkUrl = null;
            if (artwork != null && !artwork.isEmpty()) {
                artworkUrl = fileStorageService.storeFile(artwork);
            }

            Integer duration = req.getDuration() != null ? req.getDuration() : computeDuration(file);

            Video video = new Video();
            video.setTitle(req.getTitle());
            video.setArtist(artist);
            video.setGenre(genre);
            video.setJurisdiction(jurisdiction);
            video.setDescription(req.getDescription());
            video.setDuration(duration);
            video.setVideoUrl(videoUrl);
            video.setArtworkUrl(artworkUrl);
            video.setScore(0);
            video.setLevel("silver");
            video.setCreatedAt(LocalDateTime.now());

            Video savedVideo = videoRepository.save(video);
            recordRightsConfirmed("videos", "video_id", savedVideo.getVideoId());
            return savedVideo;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Surface WHICH field failed — the previous generic message made
            // this class of bug invisible in the Railway logs.
            throw new IllegalArgumentException(
                "Invalid video metadata JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Video file upload failed: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // SONG REMOVAL — the one path every deletion goes through
    //
    // The artist's delete button, account deletion, DMCA takedowns and admins
    // all end here. A removed song is never erased: the row stays (deleted_at
    // set) so award history and past leaderboards keep its title and artist.
    // Everything live drops it, and its files are moved to a hidden location
    // so saved queues, playlists and shared links stop playing it at once.
    // RemovedMediaPurgeScheduler deletes those files 30 days later.
    // =========================================================================

    /** Thrown when someone other than the owner (or an admin) tries to change a song or video. */
    public static class NotOwnerException extends RuntimeException {
        public NotOwnerException(String message) { super(message); }
    }

    /**
     * Artist or admin removes a song (DELETE /api/v1/media/song/{id}).
     * Only the song's own artist or an admin may do it. Removing a song that's
     * already removed does nothing, so a double-click is harmless.
     */
    @CacheEvict(value = {"songs", "artists", "trending", "jurisdictions", "leaderboards", "standings", "nominees", "awards", "playlists", "userProfiles"}, allEntries = true)
    public void removeSongAsOwner(UUID songId, UUID requesterId, boolean requesterIsAdmin) {
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
        boolean isOwner = song.getArtist() != null && song.getArtist().getUserId().equals(requesterId);
        if (!isOwner && !requesterIsAdmin) {
            throw new NotOwnerException("You can only delete your own songs.");
        }
        removeSong(song, isOwner ? "artist" : "admin");
    }

    /**
     * Removes a song. Safe to call more than once. Callers: removeSongAsOwner,
     * UserService (account deletion), DmcaService (takedown).
     *
     * @param reason short label for the log: artist, admin, account_deleted, dmca
     */
    @CacheEvict(value = {"songs", "artists", "trending", "jurisdictions", "leaderboards", "standings", "nominees", "awards", "playlists", "userProfiles"}, allEntries = true)
    public void removeSong(Song song, String reason) {
        if (song == null || song.isRemoved()) return;
        UUID songId = song.getSongId();

        // 1. Decide where the files will be hidden and mark the song removed.
        //    From here on the API never returns its files. The files are moved
        //    only after this transaction commits (step 5), so if anything here
        //    fails and rolls back, the song stays live with its files in place.
        String oldFileUrl = song.storedFileUrl();
        String oldArtworkUrl = song.storedArtworkUrl();
        String hiddenFileUrl = fileStorageService.hiddenLocationFor(oldFileUrl);
        String hiddenArtworkUrl = fileStorageService.hiddenLocationFor(oldArtworkUrl);

        song.setFileUrl(hiddenFileUrl);
        song.setArtworkUrl(hiddenArtworkUrl);
        song.setDeletedAt(LocalDateTime.now());
        songRepository.save(song);
        entityManager.flush();

        // 3. It leaves every playlist (active tracks and pending suggestions),
        //    the stored song counts drop to match, and the remaining tracks
        //    close the gap in their positions.
        List<UUID> playlistIds = jdbcTemplate.queryForList(
            "SELECT DISTINCT playlist_id FROM playlist_items WHERE song_id = ? AND status IN ('active','pending')",
            UUID.class, songId);
        jdbcTemplate.update("""
            UPDATE playlist p
               SET song_count = GREATEST(0, p.song_count - x.n)
              FROM (SELECT playlist_id, COUNT(*) AS n FROM playlist_items
                     WHERE song_id = ? AND status = 'active' GROUP BY playlist_id) x
             WHERE p.playlist_id = x.playlist_id
            """, songId);
        jdbcTemplate.update(
            "UPDATE playlist_items SET status = 'removed' WHERE song_id = ? AND status IN ('active','pending')",
            songId);
        for (UUID playlistId : playlistIds) {
            jdbcTemplate.update("""
                UPDATE playlist_items pi
                   SET position = r.rn - 1
                  FROM (SELECT playlist_item_id,
                               ROW_NUMBER() OVER (ORDER BY position, added_at) AS rn
                          FROM playlist_items
                         WHERE playlist_id = ? AND status = 'active') r
                 WHERE pi.playlist_item_id = r.playlist_item_id
                """, playlistId);
        }

        // 4. If it was the artist's featured song, feature their newest live
        //    song instead (or none, if this was the last one).
        if (song.getArtist() != null) {
            UUID artistId = song.getArtist().getUserId();
            jdbcTemplate.update("""
                UPDATE users
                   SET default_song_id = (SELECT s.song_id FROM songs s
                                           WHERE s.artist_id = ? AND s.deleted_at IS NULL
                                           ORDER BY s.created_at DESC LIMIT 1)
                 WHERE user_id = ? AND default_song_id = ?
                """, artistId, artistId, songId);
        }

        // 5. Move the files once the removal is committed. If storage fails,
        //    the song is still removed everywhere in the app; the file stays
        //    reachable at its old address until the 30-day purge deletes it
        //    (the purge removes the old address too), and the error is logged.
        Runnable hideFiles = () -> {
            moveQuietly(oldFileUrl, hiddenFileUrl, songId);
            moveQuietly(oldArtworkUrl, hiddenArtworkUrl, songId);
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        hideFiles.run();
                    }
                });
        } else {
            hideFiles.run();
        }

        log.info("[SongRemove] action=remove status=ok songId={} reason={} playlists={}", songId, reason, playlistIds.size());
    }

    private void moveQuietly(String fromUrl, String toUrl, UUID songId) {
        if (fromUrl == null || fromUrl.equals(toUrl)) return;
        try {
            fileStorageService.moveFile(fromUrl, toUrl);
        } catch (Exception e) {
            log.error("[SongRemove] action=hide_file status=error songId={} url={} err={} (file stays at its old address until the 30-day purge)", songId, fromUrl, e.getMessage());
        }
    }

    // Update song — EVICTS cache. Only the song's own artist (or an admin) may
    // edit it, and a removed song can't be edited.
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    public Song updateSong(UUID songId, UUID requesterId, boolean requesterIsAdmin, String description, MultipartFile artwork, String lyrics, String isrc, UUID cleanVersionId) {
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
        boolean isOwner = song.getArtist() != null && song.getArtist().getUserId().equals(requesterId);
        if (!isOwner && !requesterIsAdmin) {
            throw new NotOwnerException("You can only edit your own songs.");
        }
        if (song.isRemoved()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This song was removed and can't be edited.");
        }

        if (description != null && !description.isBlank()) {
            song.setDescription(description.trim());
        }
 
        if (artwork != null && !artwork.isEmpty()) {
            try {
                String artworkUrl = fileStorageService.storeFile(artwork);
                song.setArtworkUrl(artworkUrl);
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload artwork", e);
            }
        }
 
        if (lyrics != null) {
            song.setLyrics(lyrics);
        }
 
        if (isrc != null) {
            if (isrc.isBlank()) {
                song.setIsrc(null); // Allow clearing
            } else {
                String cleanIsrc = isrc.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                if (cleanIsrc.length() != 12) {
                    throw new RuntimeException("ISRC must be exactly 12 alphanumeric characters");
                }
                song.setIsrc(cleanIsrc);
            }
        }
 
        // NEW: Link clean version
        if (cleanVersionId != null) {
            // Verify the clean version song actually exists
            Song cleanSong = songRepository.findById(cleanVersionId)
                .orElseThrow(() -> new RuntimeException("Clean version song not found: " + cleanVersionId));
            
            if (cleanSong.isRemoved()) {
                throw new RuntimeException("Clean version song was removed");
            }

            // Verify the clean version belongs to the same artist
            if (!cleanSong.getArtist().getUserId().equals(song.getArtist().getUserId())) {
                throw new RuntimeException("Clean version must belong to the same artist");
            }
 
            // Verify the clean version is not itself explicit
            if (Boolean.TRUE.equals(cleanSong.getExplicit())) {
                throw new RuntimeException("Clean version cannot be marked as explicit");
            }
 
            song.setCleanVersionId(cleanVersionId);
        }
 
        return songRepository.save(song);
    }
 

    /**
     * Artist or admin deletes a video (DELETE /api/v1/media/video/{id}).
     * Videos never win awards, so there's no history to keep: the row, its
     * likes and its files go now (comments go with the row).
     */
    public void deleteVideoAsOwner(UUID videoId, UUID requesterId, boolean requesterIsAdmin) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        boolean isOwner = video.getArtist() != null && video.getArtist().getUserId().equals(requesterId);
        if (!isOwner && !requesterIsAdmin) {
            throw new NotOwnerException("You can only delete your own videos.");
        }
        deleteVideo(video);
    }

    /** Deletes a video, its likes and its files. Used by deleteVideoAsOwner and account deletion. */
    public void deleteVideo(Video video) {
        UUID videoId = video.getVideoId();
        String videoUrl = video.getVideoUrl();
        String artworkUrl = video.getArtworkUrl();

        jdbcTemplate.update("DELETE FROM likes WHERE media_type = 'video' AND media_id = ?", videoId);
        videoRepository.delete(video);
        entityManager.flush();

        for (String url : new String[] { videoUrl, artworkUrl }) {
            if (url == null) continue;
            try {
                fileStorageService.deleteFile(url);
            } catch (Exception e) {
                log.error("[VideoDelete] action=delete_file status=error videoId={} url={} err={}", videoId, url, e.getMessage());
            }
        }
        log.info("[VideoDelete] action=delete status=ok videoId={}", videoId);
    }

    // Play song. Not a cache eviction any more: wiping "songs" and "trending"
    // on every play (even a rejected one) kept those caches empty all day.
    // Trending refreshes every minute on its own (CacheConfig).
    public UUID playSong(UUID songId, UUID userId, String source) {
        if (userId == null) {
            log.debug(">>> Play rejected: no userId for song {}", songId);
            return null;
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        // A removed song earns nothing: no play, no points, no count. Checked
        // before anything is written. (A saved queue can still try to play it.)
        Song target = songRepository.findById(songId).orElse(null);
        if (target == null || target.isRemoved()) {
            log.debug(">>> Play rejected: song {} is removed or missing", songId);
            return null;
        }

        // 30-min cooldown (unchanged)
        String cooldownCheckSql = """
            SELECT COUNT(*) FROM song_plays
            WHERE user_id = ? AND song_id = ?
            AND played_at > NOW() - INTERVAL '30 minutes'
            """;
        Integer recentPlays = jdbcTemplate.queryForObject(cooldownCheckSql, Integer.class, userId, songId);
        if (recentPlays != null && recentPlays > 0) {
            return null;  // silent rejection, as before
        }

        // plays_today counts New York's day, the same day RaceCloseScheduler
        // resets it on. (LocalDate.now() is the server's UTC day, which rolled
        // over at 8 PM New York time and restarted the count early.)
        LocalDate today = LocalDate.now(RaceClockService.DEFAULT_ZONE);
        Query q = entityManager.createNativeQuery("""
            UPDATE songs SET plays_today = CASE
                WHEN last_play_reset_date < :today THEN 1 ELSE plays_today + 1 END,
                last_play_reset_date = :today
            WHERE song_id = :songId
            """);
        q.setParameter("today", today);
        q.setParameter("songId", songId);
        q.executeUpdate();

        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found"));
        int durationInSeconds = song.getDuration() != null ? song.getDuration() / 1000 : 180;

        // NEW: listener's home jurisdiction, snapshotted at play time (honest "where listeners are based")
        UUID listenerJurisdictionId =
            (user.getJurisdiction() != null) ? user.getJurisdiction().getJurisdictionId() : null;

        SongPlay play = SongPlay.builder()
            .song(song)
            .user(user)
            .playedAt(LocalDateTime.now())
            .durationSecs(durationInSeconds)
            .source(source)                              // NEW
            .listenerJurisdictionId(listenerJurisdictionId)  // NEW
            .completed(false)                            // NEW — set true on completion
            .platform(com.unis.util.ClientPlatform.current())  // web | ios | android, for the metrics
            .supportedArtistId(user.getSupportedArtistId())   // who this listening supports (Community Share)
            .build();
        SongPlay saved = songPlayRepository.save(play);

        Query artistQuery = entityManager.createNativeQuery("""
            UPDATE users SET total_plays = total_plays + 1
            WHERE user_id = (SELECT artist_id FROM songs WHERE song_id = :songId)
            """);
        artistQuery.setParameter("songId", songId);
        artistQuery.executeUpdate();

        entityManager.flush();
        entityManager.clear();

        scoreUpdateService.onPlay(userId, songId, "song");
        return saved.getPlayId();   // NEW — frontend needs this to complete the play
    }

    // NOT CACHED - video play
    public void playVideo(UUID videoId, UUID userId) {
        Optional<Video> optionalVideo = videoRepository.findById(videoId);
        Optional<User> optionalUser = userRepository.findById(userId);
        Video video = optionalVideo.orElseThrow(() -> new RuntimeException("Video not found"));
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        int durationInSeconds = video.getDuration() != null 
            ? video.getDuration() / 1000 
            : 180;
        
        VideoPlay play = VideoPlay.builder()
            .video(video)
            .user(user)
            .durationSecs(durationInSeconds)
            .build();
        videoPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, videoId, "video");
    }

    // CACHED: Top songs by score (5 min TTL via CacheConfig)
    @Cacheable(value = "songs", key = "'top-score-' + #jurisdictionId + '-' + #limit", sync = true)
    public List<Song> getTopSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            WHERE s.deleted_at IS NULL
            ORDER BY COALESCE(s.score, 0) DESC NULLS LAST, s.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();

        List<Song> out = results.isEmpty() ? getFallbackSongs(limit) : results;
        attachPlayCounts(out);
        initForCache(out);
        return out;
    }

    // CACHED: Trending songs by plays_today (shorter 1 min TTL via "trending" cache)
    @Cacheable(value = "trending", key = "'plays-today-' + #jurisdictionId + '-' + #limit", sync = true)
    public List<Song> getTrendingSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            WHERE s.deleted_at IS NULL
            ORDER BY COALESCE(s.plays_today, 0) DESC, s.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();

        List<Song> out = results.isEmpty() ? getFallbackSongs(limit) : results;
        attachPlayCounts(out);
        initForCache(out);
        return out;
    }

    // NOT CACHED - video methods
    public List<Video> getTopVideosByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT v.* FROM videos v
            INNER JOIN jurisdiction_hierarchy jh ON v.jurisdiction_id = jh.jurisdiction_id
            ORDER BY COALESCE(v.score, 0) DESC NULLS LAST, v.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Video.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Video> results = q.getResultList();
        List<Video> out = results.isEmpty() ? getFallbackVideos(limit) : results;
        attachVideoPlayCounts(out);
        return out;
    }

    // ── Batch helpers (one query for a whole list instead of one per song) ──

    /** Sets each song's lifetime play count with one grouped query. */
    private void attachPlayCounts(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return;
        Map<UUID, Long> counts = countBy("SELECT song_id AS id, COUNT(*) AS n FROM song_plays WHERE song_id IN (:ids) GROUP BY song_id",
                songs.stream().map(Song::getSongId).collect(Collectors.toList()));
        for (Song s : songs) s.setPlayCount(counts.getOrDefault(s.getSongId(), 0L));
    }

    /** Sets each song's like count with one grouped query. */
    private void attachLikeCounts(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return;
        Map<UUID, Long> counts = countBy("SELECT media_id AS id, COUNT(*) AS n FROM likes WHERE media_type = 'song' AND media_id IN (:ids) GROUP BY media_id",
                songs.stream().map(Song::getSongId).collect(Collectors.toList()));
        for (Song s : songs) s.setLikes(counts.getOrDefault(s.getSongId(), 0L).intValue());
    }

    /** Sets each video's lifetime play count with one grouped query. */
    private void attachVideoPlayCounts(List<Video> videos) {
        if (videos == null || videos.isEmpty()) return;
        Map<UUID, Long> counts = countBy("SELECT video_id AS id, COUNT(*) AS n FROM video_plays WHERE video_id IN (:ids) GROUP BY video_id",
                videos.stream().map(Video::getVideoId).collect(Collectors.toList()));
        for (Video v : videos) v.setPlayCount(counts.getOrDefault(v.getVideoId(), 0L));
    }

    private Map<UUID, Long> countBy(String sql, List<UUID> ids) {
        Map<UUID, Long> counts = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbcTemplate).query(sql, Map.of("ids", ids),
                (RowCallbackHandler) rs -> counts.put((UUID) rs.getObject("id"), rs.getLong("n")));
        return counts;
    }

    /**
     * Loads the lazy parts of each song's artist that the JSON includes, while
     * the database session is still open. These lists are cached and handed to
     * other requests; a cached entity with an unloaded part fails (or, worse,
     * reaches into another request's session) when the next request turns it
     * into JSON. With hibernate.default_batch_fetch_size this is one batched
     * query per kind, not one per song.
     */
    private static void initForCache(List<Song> songs) {
        if (songs == null) return;
        for (Song s : songs) {
            User artist = s.getArtist();
            if (artist != null) {
                Hibernate.initialize(artist.getJurisdiction());
                Hibernate.initialize(artist.getGenre());
            }
        }
    }

    // NOT CACHED - private fallback method. Only the rows it needs (it used to
    // load every song in the table and keep the first few).
    private List<Song> getFallbackSongs(int limit) {
        return entityManager.createQuery(
                "SELECT s FROM Song s WHERE s.deletedAt IS NULL ORDER BY s.songId", Song.class)
            .setMaxResults(Math.max(1, limit))
            .getResultList();
    }

    /**
     * Newest videos regardless of jurisdiction — used by Discover when the
     * scope is "Everywhere". Decorated with play counts so cards can show them.
     */
    public List<Video> getRecentVideos(int limit) {
        List<Video> videos = videoRepository.findAll(
                org.springframework.data.domain.PageRequest.of(
                        0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        attachVideoPlayCounts(videos);
        return videos;
    }

    // NOT CACHED - private fallback method (only the rows it needs)
    private List<Video> getFallbackVideos(int limit) {
        return videoRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.ASC, "videoId")))
            .getContent();
    }

    // ========== CACHED: an artist's live songs with likes and plays ==========
    // Was one SQL that counted every like and every play on Unis (both tables,
    // in full) and then joined, plus seven lookups per song in the row mapper.
    // Now: the artist's songs (an index lookup), then one grouped count each
    // for plays and likes. Same songs, same order, same likes/playCount fields.
    @Cacheable(value = "artists", key = "'songs-' + #artistId", sync = true)
    public List<Song> getSongsByArtist(UUID artistId) {
        List<Song> songs = songRepository.findByArtistId(artistId);
        attachPlayCounts(songs);
        attachLikeCounts(songs);
        initForCache(songs);
        return songs;
    }

    // NOT CACHED - video methods
    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }

    // ========== UPDATED: CACHED Individual song lookup with likes and plays ==========
    public Song getSongById(UUID songId) {
         // Use a fresh fetch to ensure we aren't getting a 'cleared' or 'detached' entity
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
        
        try {
            // Set play count safely
            Long playCount = songPlayRepository.countTotalPlaysBySongId(songId);
            song.setPlayCount(playCount != null ? playCount : 0L);
            
            // Set likes count safely
            int likesCount = getLikeCount(songId);
            song.setLikes(likesCount);
            
            // Ensure Artist is initialized to prevent LazyInitializationException
            if (song.getArtist() != null) {
                song.getArtist().getUsername(); // Trigger load
            }
        } catch (Exception e) {
            log.error("Error decorating song with stats: {}", e.getMessage());
            // We still return the song even if stats fail, so the page doesn't go blank
        }
        
        return song;
    }

    // NOT CACHED - video method
    // Decorated with stats + eager artist init, mirroring getSongById.
    public Video getVideoById(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));

        try {
            // Lifetime plays
            Long playCount = videoPlayRepository.countTotalPlaysByVideoId(videoId);
            video.setPlayCount(playCount != null ? playCount : 0L);

            // Plays today (query already existed on VideoRepository)
            Long playsToday = videoRepository.countPlaysToday(videoId);
            video.setPlaysToday(playsToday != null ? playsToday : 0L);

            // Likes count
            video.setLikes(getVideoLikeCount(videoId));

            // Ensure Artist is initialized to prevent LazyInitializationException
            if (video.getArtist() != null) {
                video.getArtist().getUsername(); // Trigger load
            }
            if (video.getJurisdiction() != null) {
                video.getJurisdiction().getName(); // Trigger load
            }
            if (video.getGenre() != null) {
                video.getGenre().getName(); // Trigger load
            }
        } catch (Exception e) {
            log.error("Error decorating video with stats: {}", e.getMessage());
            // We still return the video even if stats fail, so the page doesn't go blank
        }

        return video;
    }

    // CACHED: New releases (5 min TTL)
    @Cacheable(value = "songs", key = "'new-' + #jurisdictionId + '-' + #limit", sync = true)
    public List<Song> getNewSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            WHERE s.deleted_at IS NULL
            ORDER BY COALESCE(s.created_at, '1900-01-01T00:00:00') DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();

        List<Song> out = results.isEmpty() ? getFallbackSongs(limit) : results;
        attachPlayCounts(out);
        initForCache(out);
        return out;
    }

    /**
     * Newest song from each artist the viewer follows, released in the last
     * {@code days} days, newest first. Feeds the feed's "New from artists you
     * follow" row (Oct 2026). Deleted songs never show; no fallback content —
     * an empty list simply hides the row.
     */
    public List<Song> getNewFromFollowedArtists(UUID followerId, int days, int limit) {
        int safeDays = Math.max(1, Math.min(days, 30));
        int safeLimit = Math.max(1, Math.min(limit, 30));

        String query = """
            SELECT * FROM (
                SELECT DISTINCT ON (s.artist_id) s.*
                FROM songs s
                INNER JOIN follows f ON f.followed_id = s.artist_id
                WHERE f.follower_id = :followerId
                  AND s.deleted_at IS NULL
                  AND s.created_at >= :since
                ORDER BY s.artist_id, s.created_at DESC
            ) latest
            ORDER BY latest.created_at DESC
            LIMIT :limit
            """;

        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("followerId", followerId);
        q.setParameter("since", LocalDateTime.now().minusDays(safeDays));
        q.setParameter("limit", safeLimit);

        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();

        attachPlayCounts(results);
        return results;
    }

    // ========== LIKES SERVICE METHODS (NEW) ==========

    /**
     * Result of a like. pointsAwarded is what the liker earned (1 the first
     * time they ever like this track, 0 on a re-like), so the app only shows
     * the "+1" pill when points were really added.
     */
    public record LikeOutcome(boolean liked, int pointsAwarded) {}

    /**
     * Like a song
     * @param songId The song to like
     * @param userId The user liking the song
     * @return liked=false if already liked
     */

    @Transactional
    public LikeOutcome likeSong(UUID songId, UUID userId) {
        // Check if already liked
        String checkSql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, userId, songId);

        if (count != null && count > 0) {
            return new LikeOutcome(false, 0); // Already liked
        }

        Song song = songRepository.findById(songId).orElse(null);
        if (song == null || song.isRemoved()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This song was removed.");
        }

        // A like pays once per person per track, ever. Unliking and liking
        // again used to pay every time (you +1, track +2, artist +1).
        String rewardKey = "like:song:" + songId;
        boolean firstLike = scoreUpdateService.claimOnce(userId, rewardKey, 1);

        // The like keeps the date of the FIRST like, so a re-like can't count
        // again toward a later award period.
        String insertSql = """
            INSERT INTO likes (like_id, user_id, media_id, media_type, created_at)
            VALUES (?, ?, ?, 'song',
                    COALESCE((SELECT created_at FROM point_rewards WHERE user_id = ? AND reward_key = ?), NOW()))
            """;
        jdbcTemplate.update(insertSql, UUID.randomUUID(), userId, songId, userId, rewardKey);

        // Award points: Song +2, Artist +1, User +1 — first like only
        if (firstLike) {
            scoreUpdateService.onLike(userId, songId);
        }

        log.debug("✓ Like created: User {} liked Song {}{}", userId, songId, (firstLike ? " (points awarded)" : " (re-like, no points)"));

        // Only this artist's song list shows like counts; the rest of the
        // caches don't (likes used to empty "songs" and "artists" entirely).
        evictArtistSongs(song.getArtist() != null ? song.getArtist().getUserId() : null);
        return new LikeOutcome(true, firstLike ? 1 : 0);
    }
    
    /**
     * Unlike a song
     * @param songId The song to unlike
     * @param userId The user unliking the song
     * @return true if unliked, false if wasn't liked
     */
    @Transactional
    public boolean unlikeSong(UUID songId, UUID userId) {
        String deleteSql = "DELETE FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        int rowsAffected = jdbcTemplate.update(deleteSql, userId, songId);
        
        // Note: We do NOT deduct points when unliking
        // Points represent historical engagement value and remain
        
        if (rowsAffected > 0) {
            log.debug("✓ Like removed: User {} unliked Song {} (points retained)", userId, songId);
            List<UUID> artist = jdbcTemplate.queryForList("SELECT artist_id FROM songs WHERE song_id = ?", UUID.class, songId);
            evictArtistSongs(artist.isEmpty() ? null : artist.get(0));
        }
        
        return rowsAffected > 0;
    }

    /** Drops one artist's cached song list (the "songs-<artistId>" key), after the commit. */
    private void evictArtistSongs(UUID artistId) {
        if (artistId == null) return;
        org.springframework.cache.Cache artists = cacheManager.getCache("artists");
        if (artists != null) artists.evict("songs-" + artistId);
    }
    
    /**
     * Check if a user has liked a song
     * @param songId The song ID
     * @param userId The user ID
     * @return true if liked, false otherwise
     */
    public boolean isLiked(UUID songId, UUID userId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, songId);
        return count != null && count > 0;
    }
    
    /**
     * Get the total like count for a song
     * @param songId The song ID
     * @return The number of likes
     */
    public int getLikeCount(UUID songId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, songId);
        return count != null ? count : 0;
    }
    
    // ---------- VIDEO LIKES (media_type = 'video') ----------
    // The likes table is already polymorphic (media_type/media_id) and
    // computeVideoScores() already weights video likes x2 — these methods
    // simply expose the missing API surface for videos.

    // No cache holds video like counts, so nothing to evict (it used to empty
    // the whole "artists" cache on every video like).
    @Transactional
    public LikeOutcome likeVideo(UUID videoId, UUID userId) {
        String checkSql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'video'";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, userId, videoId);

        if (count != null && count > 0) {
            return new LikeOutcome(false, 0); // Already liked
        }

        if (!videoRepository.existsById(videoId)) {
            throw new ResponseStatusException(HttpStatus.GONE, "This video was deleted.");
        }

        // Same once-ever rule as songs.
        String rewardKey = "like:video:" + videoId;
        boolean firstLike = scoreUpdateService.claimOnce(userId, rewardKey, 1);

        String insertSql = """
            INSERT INTO likes (like_id, user_id, media_id, media_type, created_at)
            VALUES (?, ?, ?, 'video',
                    COALESCE((SELECT created_at FROM point_rewards WHERE user_id = ? AND reward_key = ?), NOW()))
            """;
        jdbcTemplate.update(insertSql, UUID.randomUUID(), userId, videoId, userId, rewardKey);

        // Award points: Video +2, Artist +1, User +1 (same weights as songs) — first like only
        if (firstLike) {
            scoreUpdateService.onLikeVideo(userId, videoId);
        }

        log.debug("✓ Like created: User {} liked Video {}{}", userId, videoId, (firstLike ? " (points awarded)" : " (re-like, no points)"));
        return new LikeOutcome(true, firstLike ? 1 : 0);
    }

    @Transactional
    public boolean unlikeVideo(UUID videoId, UUID userId) {
        String deleteSql = "DELETE FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'video'";
        int rowsAffected = jdbcTemplate.update(deleteSql, userId, videoId);

        // Points are retained on unlike, same policy as songs
        if (rowsAffected > 0) {
            log.debug("✓ Like removed: User {} unliked Video {} (points retained)", userId, videoId);
        }
        return rowsAffected > 0;
    }

    public boolean isVideoLiked(UUID videoId, UUID userId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'video'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, videoId);
        return count != null && count > 0;
    }

    public int getVideoLikeCount(UUID videoId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE media_id = ? AND media_type = 'video'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, videoId);
        return count != null ? count : 0;
    }

    // ========== END LIKES SERVICE METHODS ==========

    @Transactional
    public void completeSongPlay(UUID playId, double percentPlayed) {
        if (playId == null) return;
        double pct = Math.max(0, Math.min(100, percentPlayed));
        boolean completed = pct >= 80.0;   // tune this; 80% = "really listened" vs a skip
        jdbcTemplate.update(
            "UPDATE song_plays SET percent_played = ?, completed = ? WHERE play_id = ?",
            java.math.BigDecimal.valueOf(pct), completed, playId);
    }
}