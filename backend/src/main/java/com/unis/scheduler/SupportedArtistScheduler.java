package com.unis.scheduler;

import com.unis.entity.Supporter;
import com.unis.entity.User;
import com.unis.repository.SupporterRepository;
import com.unis.repository.UserRepository;
import com.unis.service.ScoreUpdateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SupportedArtistScheduler -- promotes queued supported-artist changes to
 * effective at the month boundary.
 *
 * WHY THIS IS SAFE: earnings attribution snapshots the supported artist onto
 * each ad_view row at view time (see EarningsService.trackAdView), and every
 * earnings query sums ad_views.supported_artist_id -- never re-reading
 * users.supported_artist_id at payout. So flipping the effective artist here
 * has NO retroactive effect: the departing artist keeps every cent earned
 * while they were effective. No "close earnings before promote" ordering is
 * required; this job can run independently of any payout job.
 *
 * Side effects deferred to promotion (NOT done at selection time for a change):
 *   - create the Supporter row for the new pairing
 *   - fire scoreUpdateService.onSupporterAdded for the new artist
 * These reflect reality: until the change is effective, the fan isn't really
 * supporting the new artist, so supporter counts and scores shouldn't move.
 *
 * Runs 00:05 America/New_York on the 1st of each month (tracks the Harlem
 * launch timezone, survives DST). Requires @EnableScheduling on the app class
 * (already present from the digest work).
 */
@Component
public class SupportedArtistScheduler {

    private final UserRepository userRepository;
    private final SupporterRepository supporterRepository;
    private final ScoreUpdateService scoreUpdateService;

    public SupportedArtistScheduler(
            UserRepository userRepository,
            SupporterRepository supporterRepository,
            ScoreUpdateService scoreUpdateService) {
        this.userRepository = userRepository;
        this.supporterRepository = supporterRepository;
        this.scoreUpdateService = scoreUpdateService;
    }

    @Scheduled(cron = "0 5 0 1 * *", zone = "America/New_York")
    @Transactional
    public void promotePendingSupportedArtists() {
        long startNs = System.nanoTime();

        List<User> pending;
        try {
            pending = userRepository.findByPendingSupportedArtistIdIsNotNull();
        } catch (Exception e) {
            System.err.println("[SupportPromote] action=run status=error phase=query err=" + e.getMessage());
            return;
        }

        int total = pending.size();
        int promoted = 0, skippedDeleted = 0, skippedNoop = 0, failed = 0;

        for (User fan : pending) {
            UUID fanId = fan.getUserId();
            UUID newArtistId = fan.getPendingSupportedArtistId();
            UUID oldArtistId = fan.getSupportedArtistId();

            try {
                // Target deleted (or vanished) before promotion: clear pending, keep current.
                Optional<User> newArtistOpt = userRepository.findById(newArtistId);
                if (newArtistOpt.isEmpty() || newArtistOpt.get().getDeletedAt() != null
                        || !"artist".equals(newArtistOpt.get().getRole().toString())) {
                    fan.setPendingSupportedArtistId(null);
                    fan.setPendingSupportedArtistSince(null);
                    userRepository.save(fan);
                    skippedDeleted++;
                    System.out.println("[SupportPromote] action=promote userId=" + fanId
                        + " status=skip reason=target_invalid target=" + newArtistId);
                    continue;
                }

                // Pending equals current (e.g. re-pick edge): just clear pending.
                if (newArtistId.equals(oldArtistId)) {
                    fan.setPendingSupportedArtistId(null);
                    fan.setPendingSupportedArtistSince(null);
                    userRepository.save(fan);
                    skippedNoop++;
                    System.out.println("[SupportPromote] action=promote userId=" + fanId
                        + " status=skip reason=already_effective artist=" + newArtistId);
                    continue;
                }

                User newArtist = newArtistOpt.get();

                // Remove the old supporter relationship (the new effective month begins).
                if (oldArtistId != null) {
                    supporterRepository.deleteByListenerUserIdAndArtistUserId(fanId, oldArtistId);
                }

                // Flip the effective pointer.
                fan.setSupportedArtistId(newArtistId);
                fan.setPendingSupportedArtistId(null);
                fan.setPendingSupportedArtistSince(null);
                userRepository.save(fan);

                // Create the new supporter row + award points -- deferred side effects.
                Supporter supporter = Supporter.builder()
                        .listener(fan)
                        .artist(newArtist)
                        .createdAt(LocalDateTime.now())
                        .build();
                supporterRepository.save(supporter);
                scoreUpdateService.onSupporterAdded(newArtistId);

                promoted++;
                System.out.println("[SupportPromote] action=promote userId=" + fanId
                    + " status=ok from=" + oldArtistId + " to=" + newArtistId);

            } catch (Exception e) {
                failed++;
                System.err.println("[SupportPromote] action=promote userId=" + fanId
                    + " status=error err=" + e.getMessage());
                // Continue with the rest of the batch.
            }
        }

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        System.out.println("[SupportPromote] action=run status=ok total=" + total
            + " promoted=" + promoted + " skippedDeleted=" + skippedDeleted
            + " skippedNoop=" + skippedNoop + " failed=" + failed + " durationMs=" + ms);
    }
}