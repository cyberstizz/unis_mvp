package com.unis.controller;

import com.unis.entity.ArtistPhoto;
import com.unis.service.ArtistPhotoService;
import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Profile gallery photos (artists and listeners).
 *
 *   GET    /api/v1/users/{ownerId}/photos                       public route, gated per viewer (see below)
 *   POST   /api/v1/users/{ownerId}/photos            multipart  self-only
 *   DELETE /api/v1/users/{ownerId}/photos/{photoId}             self-only
 *   POST   /api/v1/users/{ownerId}/photos/{photoId}/like        authenticated
 *   DELETE /api/v1/users/{ownerId}/photos/{photoId}/like        authenticated
 *
 * The GET is permitAll in SecurityConfig; everything else falls through to the
 * /api/v1/** authenticated catch-all. Upload/delete additionally enforce that
 * the caller is the owner. Likes award no points (see ArtistPhotoService).
 *
 * GET response:
 *   { photos: [PhotoView...], max: 15, hidden: bool, visibility?: string }
 *   hidden     — true when this viewer may not see the gallery (photos is []).
 *                Restricted galleries (under 18, no DOB on a listener, or
 *                "Public profile" off) are shown only to the owner and mutual
 *                follows — see ArtistPhotoService.canView.
 *   visibility — owner only: everyone | private | under18 | noBirthdate.
 */
@RestController
@RequestMapping("/api/v1/users")
public class ArtistPhotoController {

    private static final Logger log = LoggerFactory.getLogger(ArtistPhotoController.class);

    private final ArtistPhotoService service;

    public ArtistPhotoController(ArtistPhotoService service) {
        this.service = service;
    }

    /** Signed-in user's id, or null for guests (anonymous token has no UUID credentials). */
    private static UUID currentUserIdOrNull() {
        try {
            return SecurityUtils.getAuthenticatedUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @GetMapping("/{artistId}/photos")
    public ResponseEntity<?> list(@PathVariable UUID artistId) {
        try {
            ArtistPhotoService.PhotoList result = service.listForViewer(artistId, currentUserIdOrNull());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("photos", result.photos());
            body.put("max", ArtistPhotoService.MAX_PHOTOS);
            body.put("hidden", result.hidden());
            if (result.visibility() != null) body.put("visibility", result.visibility().wire);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Photo list failed for {}: {}", artistId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not load photos."));
        }
    }

    @PostMapping(value = "/{artistId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable UUID artistId,
                                    @RequestPart("file") MultipartFile file) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        if (!me.equals(artistId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You can only edit your own photos."));
        }
        try {
            ArtistPhoto saved = service.add(artistId, file);
            return ResponseEntity.ok(saved);
        } catch (IllegalStateException e) {            // limit reached
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {         // bad/empty/oversized file
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Photo upload failed for {}: {}", artistId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed. Please try again."));
        }
    }

    @DeleteMapping("/{artistId}/photos/{photoId}")
    public ResponseEntity<?> delete(@PathVariable UUID artistId, @PathVariable UUID photoId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        if (!me.equals(artistId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You can only edit your own photos."));
        }
        try {
            service.delete(artistId, photoId);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{ownerId}/photos/{photoId}/like")
    public ResponseEntity<?> like(@PathVariable UUID ownerId, @PathVariable UUID photoId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        try {
            long count = service.like(ownerId, photoId, me);
            return ResponseEntity.ok(Map.of("liked", true, "likeCount", count));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Photo like failed {} by {}: {}", photoId, me, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not like that photo."));
        }
    }

    @DeleteMapping("/{ownerId}/photos/{photoId}/like")
    public ResponseEntity<?> unlike(@PathVariable UUID ownerId, @PathVariable UUID photoId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        try {
            long count = service.unlike(ownerId, photoId, me);
            return ResponseEntity.ok(Map.of("liked", false, "likeCount", count));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Photo unlike failed {} by {}: {}", photoId, me, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not unlike that photo."));
        }
    }
}
