package com.unis.service;

import com.unis.dto.PhotoView;
import com.unis.entity.ArtistPhoto;
import com.unis.entity.User;
import com.unis.repository.ArtistPhotoRepository;
import com.unis.repository.FollowRepository;
import com.unis.repository.UserBlockRepository;
import com.unis.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Profile gallery photos — used by BOTH artists (artist page + dashboard) and
 * listeners (the photo grid on /user/:id). The table is still called
 * artist_photos for history; rows are keyed by the owner's user_id in
 * artist_id and nothing here checks role.
 *
 * Stored in Cloudflare R2 via the existing FileStorageService; one row per
 * photo in artist_photos. Likes live in photo_likes (see
 * backend/sql/V2026_09__photo_likes.sql) and are deliberately separate from the
 * song/video `likes` table: photo likes award NO points and must never be
 * counted by anything that scores songs, artists or listeners.
 *
 * VISIBILITY (the under-18 safeguard + the Account Settings "Public profile"
 * toggle). A gallery is RESTRICTED when any of these is true:
 *   - the owner is under 18 (from date_of_birth),
 *   - the owner is a listener with no date_of_birth on file (registration does
 *     not require one server-side, so unknown is treated as possibly a minor;
 *     artists with no DOB stay public so existing artist galleries don't vanish),
 *   - the owner turned "Public profile" off.
 * A restricted gallery is visible only to the owner and to MUTUAL follows
 * (owner follows the viewer AND the viewer follows the owner). Logged-out
 * visitors never see it. Ages are self-declared, so "other minors can see
 * minors" is deliberately NOT a rule — an adult claiming to be 15 would see
 * every teen's photos. Independently of the above: a viewer the owner blocked
 * sees nothing, and a soft-deleted owner's gallery is gone for everyone.
 * The same gate guards likes, so you can't like what you can't see.
 *
 * MAX_PHOTOS is the single source of truth for the cap — change it here and the
 * frontend reads the same number.
 */
@Service
public class ArtistPhotoService {

    public static final int MAX_PHOTOS = 15;

    private final ArtistPhotoRepository repo;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;
    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    public ArtistPhotoService(ArtistPhotoRepository repo,
                              FileStorageService fileStorageService,
                              JdbcTemplate jdbcTemplate,
                              UserBlockRepository userBlockRepository,
                              UserRepository userRepository,
                              FollowRepository followRepository) {
        this.repo = repo;
        this.fileStorageService = fileStorageService;
        this.jdbcTemplate = jdbcTemplate;
        this.userBlockRepository = userBlockRepository;
        this.userRepository = userRepository;
        this.followRepository = followRepository;
    }

    /** Why a gallery is (or isn't) restricted. Wire values go to the owner only. */
    public enum Visibility {
        EVERYONE("everyone"),
        PRIVATE("private"),
        UNDER_18("under18"),
        NO_BIRTHDATE("noBirthdate");

        public final String wire;
        Visibility(String wire) { this.wire = wire; }
    }

    /**
     * What the list endpoint returns.
     *   hidden     — true when the viewer isn't allowed to see this gallery
     *                (photos is then empty). Same value for "private" and
     *                "under 18", so a stranger can't tell which applies.
     *   visibility — only filled in when the viewer IS the owner, so the
     *                profile can tell them who sees their photos; null otherwise.
     */
    public record PhotoList(List<PhotoView> photos, boolean hidden, Visibility visibility) {}

    public Visibility visibilityOf(User owner) {
        if (Boolean.FALSE.equals(owner.getPublicProfile())) return Visibility.PRIVATE;
        LocalDate dob = owner.getDateOfBirth();
        if (dob != null) {
            return Period.between(dob, LocalDate.now()).getYears() < 18
                    ? Visibility.UNDER_18
                    : Visibility.EVERYONE;
        }
        return owner.getRole() == User.Role.artist ? Visibility.EVERYONE : Visibility.NO_BIRTHDATE;
    }

    /** The single gate for seeing (and therefore liking) someone's photos. */
    public boolean canView(User owner, UUID viewerId) {
        if (owner.isDeleted()) return false;
        UUID ownerId = owner.getUserId();
        if (viewerId != null && viewerId.equals(ownerId)) return true;
        if (viewerId != null && userBlockRepository.existsByBlockerIdAndBlockedId(ownerId, viewerId)) return false;
        if (visibilityOf(owner) == Visibility.EVERYONE) return true;
        if (viewerId == null) return false;
        return followRepository.existsByFollower_UserIdAndFollowed_UserId(ownerId, viewerId)
            && followRepository.existsByFollower_UserIdAndFollowed_UserId(viewerId, ownerId);
    }

    public List<ArtistPhoto> list(UUID artistId) {
        return repo.findByArtistIdOrderByPositionAscCreatedAtAsc(artistId);
    }

    /**
     * The gallery as this viewer is allowed to see it.
     *
     * @param viewerId the signed-in viewer, or null for guests
     */
    public PhotoList listForViewer(UUID ownerId, UUID viewerId) {
        User owner = userRepository.findById(ownerId).orElse(null);
        if (owner == null) return new PhotoList(List.of(), false, null);

        boolean isOwner = ownerId.equals(viewerId);
        Visibility visibility = isOwner ? visibilityOf(owner) : null;
        if (!canView(owner, viewerId)) return new PhotoList(List.of(), true, visibility);

        return new PhotoList(withLikes(ownerId, viewerId), false, visibility);
    }

    /**
     * Photos plus like data in two queries total (photos, then one grouped
     * count) — never one query per photo. No visibility check: callers gate.
     */
    private List<PhotoView> withLikes(UUID ownerId, UUID viewerId) {
        List<ArtistPhoto> photos = list(ownerId);
        if (photos.isEmpty()) return List.of();

        Map<UUID, Long> counts = new HashMap<>();
        Set<UUID> likedByViewer = new HashSet<>();

        if (viewerId == null) {
            jdbcTemplate.query(
                "SELECT pl.photo_id, COUNT(*) AS n " +
                "FROM photo_likes pl JOIN artist_photos p ON p.photo_id = pl.photo_id " +
                "WHERE p.artist_id = ? GROUP BY pl.photo_id",
                (RowCallbackHandler) rs -> counts.put(rs.getObject("photo_id", UUID.class), rs.getLong("n")),
                ownerId);
        } else {
            jdbcTemplate.query(
                "SELECT pl.photo_id, COUNT(*) AS n, BOOL_OR(pl.user_id = ?) AS mine " +
                "FROM photo_likes pl JOIN artist_photos p ON p.photo_id = pl.photo_id " +
                "WHERE p.artist_id = ? GROUP BY pl.photo_id",
                (RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("photo_id", UUID.class);
                    counts.put(id, rs.getLong("n"));
                    if (rs.getBoolean("mine")) likedByViewer.add(id);
                },
                viewerId, ownerId);
        }

        return photos.stream()
                .map(p -> new PhotoView(
                        p.getPhotoId(),
                        p.getArtistId(),
                        p.getPhotoUrl(),
                        p.getPosition(),
                        p.getCreatedAt(),
                        counts.getOrDefault(p.getPhotoId(), 0L),
                        likedByViewer.contains(p.getPhotoId())))
                .toList();
    }

    @Transactional
    public ArtistPhoto add(UUID artistId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image provided.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("Each image must be 10MB or smaller.");
        }

        long count = repo.countByArtistId(artistId);
        if (count >= MAX_PHOTOS) {
            throw new IllegalStateException("You've reached the " + MAX_PHOTOS + "-photo limit. Remove one to add another.");
        }

        String url = fileStorageService.storeFile(file);

        ArtistPhoto photo = ArtistPhoto.builder()
                .artistId(artistId)
                .photoUrl(url)
                .position((int) count)
                .createdAt(LocalDateTime.now())
                .build();
        return repo.save(photo);
    }

    @Transactional
    public void delete(UUID artistId, UUID photoId) {
        ArtistPhoto photo = repo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found."));
        if (!photo.getArtistId().equals(artistId)) {
            throw new SecurityException("You can only remove your own photos.");
        }
        try {
            fileStorageService.deleteFile(photo.getPhotoUrl());
        } catch (Exception ignored) {
            // best-effort R2 cleanup; never block the row delete on a storage hiccup
        }
        // photo_likes rows go with it via ON DELETE CASCADE
        repo.delete(photo);
    }

    // ------------------------------------------------------------------
    // Likes — no points, no score updates, no cache eviction needed
    // (the photo list is never cached server- or client-side).
    // ------------------------------------------------------------------

    /**
     * Idempotent: liking twice is a no-op. Returns the new like count.
     * Anyone who can't see the gallery (blocked, not a mutual on a restricted
     * gallery) gets the same "not found" as a bad id, so nothing is revealed.
     */
    @Transactional
    public long like(UUID ownerId, UUID photoId, UUID userId) {
        requireVisiblePhoto(ownerId, photoId, userId);
        jdbcTemplate.update(
            "INSERT INTO photo_likes (photo_id, user_id, created_at) VALUES (?, ?, NOW()) " +
            "ON CONFLICT (photo_id, user_id) DO NOTHING",
            photoId, userId);
        return likeCount(photoId);
    }

    /** Idempotent: unliking something you never liked is a no-op. Returns the new count. */
    @Transactional
    public long unlike(UUID ownerId, UUID photoId, UUID userId) {
        requireVisiblePhoto(ownerId, photoId, userId);
        jdbcTemplate.update("DELETE FROM photo_likes WHERE photo_id = ? AND user_id = ?", photoId, userId);
        return likeCount(photoId);
    }

    public long likeCount(UUID photoId) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM photo_likes WHERE photo_id = ?", Long.class, photoId);
        return n == null ? 0L : n;
    }

    /**
     * The URL carries the owner id: make sure the photo really is theirs AND
     * that this user is allowed to see it. Every failure is the same 404.
     */
    private void requireVisiblePhoto(UUID ownerId, UUID photoId, UUID userId) {
        ArtistPhoto photo = repo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found."));
        if (!photo.getArtistId().equals(ownerId)) {
            throw new IllegalArgumentException("Photo not found.");
        }
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found."));
        if (!canView(owner, userId)) {
            throw new IllegalArgumentException("Photo not found.");
        }
    }
}
