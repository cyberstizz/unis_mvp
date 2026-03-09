package com.unis.service;

import com.unis.entity.DmcaClaim;
import com.unis.entity.DmcaCounterNotice;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.DmcaClaimRepository;
import com.unis.repository.DmcaCounterNoticeRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DmcaService {

    @Autowired
    private DmcaClaimRepository dmcaClaimRepository;

    @Autowired
    private DmcaCounterNoticeRepository dmcaCounterNoticeRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModerationService moderationService;

    /**
     * Submit a new DMCA claim (called from public endpoint)
     */
    public DmcaClaim submitClaim(String claimantName, String claimantEmail,
                                  String claimantPhone, String claimantCompany,
                                  String copyrightOwner, String workDescription,
                                  String originalWorkUrl, String infringingUrl,
                                  String signature) {

        DmcaClaim claim = DmcaClaim.builder()
                .claimantName(claimantName)
                .claimantEmail(claimantEmail)
                .claimantPhone(claimantPhone)
                .claimantCompany(claimantCompany)
                .copyrightOwner(copyrightOwner)
                .workDescription(workDescription)
                .originalWorkUrl(originalWorkUrl)
                .infringingUrl(infringingUrl)
                .status("submitted")
                .build();

        return dmcaClaimRepository.save(claim);
    }

    /**
     * Update claim status (admin action)
     */
    public DmcaClaim updateClaimStatus(UUID claimId, String newStatus,
                                        UUID adminUserId, String notes) {
        DmcaClaim claim = dmcaClaimRepository.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));

        // Validate status transition
        validateStatusTransition(claim.getStatus(), newStatus);

        String oldStatus = claim.getStatus();
        claim.setStatus(newStatus);
        claim.setResolutionNotes(notes);

        if ("upheld".equals(newStatus) || "rejected".equals(newStatus)) {
            User admin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));
            claim.setResolvedBy(admin);
            claim.setResolvedAt(LocalDateTime.now());
        }

        if ("reviewing".equals(newStatus)) {
            User admin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));
            claim.setAssignedTo(admin);
        }

        DmcaClaim saved = dmcaClaimRepository.save(claim);

        // Log the moderation action
        moderationService.logAction(adminUserId, "dmca_" + newStatus, "dmca_claim",
                claimId, notes, "Status changed from " + oldStatus + " to " + newStatus);

        return saved;
    }

    /**
     * Execute content takedown for an upheld claim
     */
    public void executeTakedown(UUID claimId, UUID adminUserId) {
        DmcaClaim claim = dmcaClaimRepository.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));

        if (!"upheld".equals(claim.getStatus())) {
            throw new RuntimeException("Can only execute takedown on upheld claims");
        }

        // If a specific song was identified, soft-delete it
        if (claim.getInfringSong() != null) {
            Song song = claim.getInfringSong();
            song.setDeletedAt(LocalDateTime.now());
            songRepository.save(song);

            moderationService.logAction(adminUserId, "content_removed", "song",
                    song.getSongId(), "DMCA takedown for claim " + claimId, null);
        }
    }

    /**
     * File a counter-notice (called by the song uploader)
     */
    public DmcaCounterNotice fileCounterNotice(UUID claimId, UUID respondentUserId,
                                                 String respondentName, String respondentEmail,
                                                 String statement, boolean consentToJurisdiction,
                                                 String signature) {
        DmcaClaim claim = dmcaClaimRepository.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));

        if (!"upheld".equals(claim.getStatus())) {
            throw new RuntimeException("Counter-notice can only be filed against upheld claims");
        }

        User respondent = userRepository.findById(respondentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        DmcaCounterNotice counterNotice = DmcaCounterNotice.builder()
                .claim(claim)
                .respondentUser(respondent)
                .respondentName(respondentName)
                .respondentEmail(respondentEmail)
                .statement(statement)
                .consentToJurisdiction(consentToJurisdiction)
                .signature(signature)
                .status("filed")
                .restoreEligibleAt(LocalDateTime.now().plusDays(14))
                .build();

        DmcaCounterNotice saved = dmcaCounterNoticeRepository.save(counterNotice);

        // Update claim status
        claim.setStatus("counter_pending");
        dmcaClaimRepository.save(claim);

        return saved;
    }

    /**
     * Get paginated claims, optionally filtered by status
     */
    @Transactional(readOnly = true)
    public Page<DmcaClaim> getClaims(String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status != null && !status.isBlank()) {
            return dmcaClaimRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
        }
        return dmcaClaimRepository.findAllByOrderByCreatedAtDesc(pageRequest);
    }

    /**
     * Get a single claim by ID
     */
    @Transactional(readOnly = true)
    public DmcaClaim getClaimById(UUID claimId) {
        return dmcaClaimRepository.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));
    }

    /**
     * Get counter-notice for a claim
     */
    @Transactional(readOnly = true)
    public DmcaCounterNotice getCounterNoticeForClaim(UUID claimId) {
        return dmcaCounterNoticeRepository.findByClaimId(claimId).orElse(null);
    }

    /**
     * Get upheld claims against an artist's songs
     */
    @Transactional(readOnly = true)
    public List<DmcaClaim> getUpheldClaimsAgainstArtist(UUID artistId) {
        return dmcaClaimRepository.findUpheldClaimsAgainstArtist(artistId);
    }

    /**
     * Validate that a status transition is legal
     */
    private void validateStatusTransition(String currentStatus, String newStatus) {
        boolean valid = switch (currentStatus) {
            case "submitted" -> "reviewing".equals(newStatus);
            case "reviewing" -> "upheld".equals(newStatus) || "rejected".equals(newStatus);
            case "upheld" -> "counter_pending".equals(newStatus);
            case "counter_pending" -> "resolved".equals(newStatus);
            default -> false;
        };

        if (!valid) {
            throw new RuntimeException("Invalid status transition: " + currentStatus + " → " + newStatus);
        }
    }
}