package com.unis.controller;

import com.unis.entity.ArtistPhoto;
import com.unis.service.ArtistPhotoService;
import com.unis.util.SecurityUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artist gallery photos.
 *
 *   GET    /api/v1/users/{artistId}/photos              public  (for the artist page later)
 *   POST   /api/v1/users/{artistId}/photos   multipart  self-only
 *   DELETE /api/v1/users/{artistId}/photos/{photoId}    self-only
 *
 * The GET is made permitAll in SecurityConfig; POST/DELETE fall through to the
 * default authenticated rule and additionally enforce that the caller is the
 * artist themselves.
 */
@RestController
@RequestMapping("/api/v1/users")
public class ArtistPhotoController {

    private final ArtistPhotoService service;

    public ArtistPhotoController(ArtistPhotoService service) {
        this.service = service;
    }

    @GetMapping("/{artistId}/photos")
    public ResponseEntity<?> list(@PathVariable UUID artistId) {
        List<ArtistPhoto> photos = service.list(artistId);
        return ResponseEntity.ok(Map.of(
            "photos", photos,
            "max", ArtistPhotoService.MAX_PHOTOS
        ));
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
}