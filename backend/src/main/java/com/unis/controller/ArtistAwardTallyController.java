package com.unis.controller;

import com.unis.dto.AwardTallyDto;
import com.unis.repository.AwardTallyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Artist award tallies for the artist page medal rails.
 *
 * <p><b>GET /api/v1/users/{artistId}/awards</b>
 *
 * <p>This path is what {@code artistpage.jsx} has been calling all along. It
 * was never implemented, so the request 404'd, the frontend's
 * {@code .catch(() => ({ data: [] }))} swallowed it, and the medal rail
 * silently rendered nothing. This controller fills that gap.
 *
 * <p>Lives in its own class rather than inside {@code UserController} so the
 * feature drops in without editing any existing file. Spring resolves this
 * fine: the base path is shared, but no other controller maps
 * {@code GET /{userId}/awards}.
 *
 * <p>Read-only and public, matching the other artist-page reads
 * (followers/count, photos) — guests browsing an artist should see the same
 * prestige a logged-in fan sees.
 */
@RestController
@RequestMapping("/api/v1/users")
public class ArtistAwardTallyController {

    private static final Logger log = LoggerFactory.getLogger(ArtistAwardTallyController.class);

    private final AwardTallyRepository awardTallyRepository;

    public ArtistAwardTallyController(AwardTallyRepository awardTallyRepository) {
        this.awardTallyRepository = awardTallyRepository;
    }

    @GetMapping("/{artistId}/awards")
    public ResponseEntity<List<AwardTallyDto>> getArtistAwardTally(@PathVariable UUID artistId) {
        try {
            List<AwardTallyDto> tally = new ArrayList<>();
            tally.addAll(awardTallyRepository.tallyArtistAwards(artistId));
            tally.addAll(awardTallyRepository.tallySongAwards(artistId));

            long totalWins = tally.stream().mapToLong(AwardTallyDto::getCount).sum();
            log.info("[Awards] action=tally status=ok artistId={} rows={} totalWins={}",
                    artistId, tally.size(), totalWins);

            return ResponseEntity.ok(tally);
        } catch (Exception e) {
            // An artist page must still render if the tally fails — return an
            // empty rail rather than breaking the whole page load.
            log.error("[Awards] action=tally status=fail artistId={} err={}",
                    artistId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}