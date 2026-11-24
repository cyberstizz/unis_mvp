package com.unis.controller;

import com.unis.service.ReleaseService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Albums, EPs, mixtapes and singles.
 *
 *   GET    /api/v1/releases/artist/{artistId}   an artist's releases (public)
 *   GET    /api/v1/releases/{releaseId}         one release with its songs in order (public)
 *   POST   /api/v1/releases                     multipart: release (JSON), cover (image, optional)
 *   PATCH  /api/v1/releases/{releaseId}         multipart: release (JSON, any fields), cover (optional)
 *   DELETE /api/v1/releases/{releaseId}         its songs go back to being singles
 *
 * release JSON: { "title", "type": "ALBUM|EP|MIXTAPE|SINGLE", "description", "songIds": [...] }
 * Writes are the artist's own, or a Manager's acting for them (TeamActAsFilter).
 */
@RestController
@RequestMapping("/api/v1/releases")
@Slf4j
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping("/artist/{artistId}")
    public ResponseEntity<List<ReleaseService.ReleaseView>> forArtist(@PathVariable UUID artistId) {
        return ResponseEntity.ok(releaseService.forArtist(artistId));
    }

    @GetMapping("/{releaseId}")
    public ResponseEntity<?> get(@PathVariable UUID releaseId) {
        try {
            return ResponseEntity.ok(releaseService.get(releaseId));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@RequestPart("release") String json,
                                    @RequestPart(value = "cover", required = false) MultipartFile cover) {
        try {
            return ResponseEntity.ok(releaseService.create(SecurityUtils.getAuthenticatedUserId(), json, cover));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    @PatchMapping(value = "/{releaseId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> update(@PathVariable UUID releaseId,
                                    @RequestPart(value = "release", required = false) String json,
                                    @RequestPart(value = "cover", required = false) MultipartFile cover) {
        try {
            return ResponseEntity.ok(releaseService.update(releaseId, SecurityUtils.getAuthenticatedUserId(), json, cover));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    @DeleteMapping("/{releaseId}")
    public ResponseEntity<?> delete(@PathVariable UUID releaseId) {
        try {
            releaseService.delete(releaseId, SecurityUtils.getAuthenticatedUserId());
            return ResponseEntity.ok(Map.of("removed", true));
        } catch (ResponseStatusException e) {
            return error(e);
        }
    }

    private static ResponseEntity<Map<String, String>> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }
}
