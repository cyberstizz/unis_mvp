package com.unis.service;

import com.unis.dto.PreRegistrationRequest;
import com.unis.dto.PreRegistrationResponse;
import com.unis.entity.PreRegistration;
import com.unis.repository.PreRegistrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PreRegistrationService {

    @Autowired
    private PreRegistrationRepository preRegRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ─── Major metro threshold tiers ───
    // In production, pull these from jurisdiction_activation_thresholds table
    private static final Set<String> MAJOR_METROS = Set.of(
        "Greater Los Angeles", "Greater Chicago", "Greater Atlanta",
        "Greater Houston", "Greater Miami", "Greater Dallas",
        "Greater Phoenix", "Greater Philadelphia", "San Francisco Bay Area",
        "Greater Seattle", "Greater Boston", "Greater Denver",
        "Greater Detroit", "Greater Minneapolis", "Greater Washington DC"
    );

    private static final Set<String> MID_MARKETS = Set.of(
        "Greater Nashville", "Greater Memphis", "Greater New Orleans",
        "Greater Charlotte", "Greater Las Vegas", "Greater Austin",
        "Greater Portland", "Greater San Antonio", "Greater San Diego",
        "Greater Tampa", "Greater Orlando", "Greater Sacramento",
        "Greater Kansas City", "Greater Columbus", "Greater St. Louis",
        "Greater Baltimore", "Greater Milwaukee", "Greater Indianapolis",
        "Greater Cleveland", "Greater Pittsburgh"
    );

    private int getThresholdForRegion(String metroRegion) {
        if (MAJOR_METROS.contains(metroRegion)) return 1000;
        if (MID_MARKETS.contains(metroRegion)) return 500;
        return 250;
    }

    // ─── Register ───
    @Transactional
    public PreRegistrationResponse register(PreRegistrationRequest req) {
        // Validate uniqueness
        if (preRegRepo.existsByEmail(req.getEmail().toLowerCase().trim())) {
            throw new IllegalArgumentException("Email already registered on the waitlist");
        }
        if (preRegRepo.existsByUsername(req.getUsername().toLowerCase().trim())) {
            throw new IllegalArgumentException("Username already taken");
        }

        // Validate referral code if provided
        if (req.getReferredByCode() != null && !req.getReferredByCode().isBlank()) {
            if (!preRegRepo.existsByReferralCode(req.getReferredByCode().trim().toUpperCase())) {
                throw new IllegalArgumentException("Invalid referral code");
            }
        }

        // Build entity
        PreRegistration entity = new PreRegistration();
        entity.setEmail(req.getEmail().toLowerCase().trim());
        entity.setUsername(req.getUsername().toLowerCase().trim());
        entity.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        entity.setDisplayName(req.getDisplayName() != null ? req.getDisplayName().trim() : req.getUsername().trim());
        entity.setUserType(req.getUserType() != null ? req.getUserType().toUpperCase() : "LISTENER");
        entity.setStateCode(req.getStateCode().toUpperCase().trim());
        entity.setStateName(req.getStateName().trim());

        // If "Other" was selected, use freetext as the metro region
        String metro = req.getMetroRegion().trim();
        if ("Other".equalsIgnoreCase(metro) && req.getCityFreetext() != null) {
            entity.setMetroRegion(req.getCityFreetext().trim());
            entity.setCityFreetext(req.getCityFreetext().trim());
        } else {
            entity.setMetroRegion(metro);
        }

        if (req.getReferredByCode() != null && !req.getReferredByCode().isBlank()) {
            entity.setReferredBy(req.getReferredByCode().trim().toUpperCase());
        }

        // Generate unique referral code: UNIS-XXXXXX
        entity.setReferralCode(generateUniqueReferralCode());

        PreRegistration saved = preRegRepo.save(entity);
        return toResponse(saved);
    }

    // ─── Lookup by referral code (for validation) ───
    public Optional<PreRegistrationResponse> findByReferralCode(String code) {
        return preRegRepo.findByReferralCode(code.toUpperCase().trim()).map(this::toResponse);
    }

    // ─── Region progress for a specific state+metro ───
    public Map<String, Object> getRegionProgress(String stateCode, String metroRegion) {
        long count = preRegRepo.countByStateCodeAndMetroRegion(stateCode, metroRegion);
        int threshold = getThresholdForRegion(metroRegion);
        double percent = Math.min(100.0, (count * 100.0) / threshold);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stateCode", stateCode);
        result.put("metroRegion", metroRegion);
        result.put("signupCount", count);
        result.put("threshold", threshold);
        result.put("progressPercent", Math.round(percent * 10.0) / 10.0);
        result.put("readyToActivate", count >= threshold);
        return result;
    }

    // ─── Admin: full waitlist overview ───
    public Map<String, Object> getWaitlistOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        long total = preRegRepo.count();
        long pending = preRegRepo.countByConverted(false);
        long converted = preRegRepo.countByConverted(true);
        long artists = preRegRepo.countByUserType("ARTIST");
        long listeners = preRegRepo.countByUserType("LISTENER");
        long today = preRegRepo.countSignupsToday(LocalDate.now().atStartOfDay());

        overview.put("totalPreRegistrations", total);
        overview.put("totalPending", pending);
        overview.put("totalConverted", converted);
        overview.put("totalArtists", artists);
        overview.put("totalListeners", listeners);
        overview.put("signupsToday", today);

        // Top regions
        List<Object[]> topRegions = preRegRepo.findTopRegions();
        List<Map<String, Object>> regionList = topRegions.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stateCode", row[0]);
            m.put("stateName", row[1]);
            m.put("metroRegion", row[2]);
            m.put("count", ((Number) row[3]).longValue());
            String metro = (String) row[2];
            int threshold = getThresholdForRegion(metro);
            long cnt = ((Number) row[3]).longValue();
            m.put("threshold", threshold);
            m.put("progressPercent", Math.round((cnt * 1000.0) / threshold) / 10.0);
            return m;
        }).collect(Collectors.toList());
        overview.put("topRegions", regionList);

        // State counts (for heatmap)
        List<Object[]> stateCounts = preRegRepo.findCountsByState();
        Map<String, Long> stateMap = new LinkedHashMap<>();
        for (Object[] row : stateCounts) {
            stateMap.put((String) row[0], ((Number) row[1]).longValue());
        }
        overview.put("signupsByState", stateMap);

        // Top referrers
        List<Object[]> topRefs = preRegRepo.findTopReferrers();
        List<Map<String, Object>> refList = topRefs.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", row[0]);
            m.put("referralCode", row[1]);
            m.put("stateCode", row[2]);
            m.put("metroRegion", row[3]);
            m.put("referralCount", ((Number) row[4]).longValue());
            return m;
        }).collect(Collectors.toList());
        overview.put("topReferrers", refList);

        return overview;
    }

    // ─── Admin: daily signup trend ───
    public Map<String, Long> getWaitlistDailySignups(int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = preRegRepo.findDailySignups(since);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return result;
    }

    // ─── Referral code generation ───
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1
    private static final SecureRandom RANDOM = new SecureRandom();

    private String generateUniqueReferralCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("UNIS-");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (!preRegRepo.existsByReferralCode(code)) {
                return code;
            }
        }
        throw new RuntimeException("Failed to generate unique referral code after 20 attempts");
    }

    // ─── Entity → Response DTO ───
    private PreRegistrationResponse toResponse(PreRegistration entity) {
        PreRegistrationResponse r = new PreRegistrationResponse();
        r.setId(entity.getId());
        r.setUsername(entity.getUsername());
        r.setDisplayName(entity.getDisplayName());
        r.setEmail(entity.getEmail());
        r.setUserType(entity.getUserType());
        r.setStateCode(entity.getStateCode());
        r.setStateName(entity.getStateName());
        r.setMetroRegion(entity.getMetroRegion());
        r.setReferralCode(entity.getReferralCode());
        r.setReferredBy(entity.getReferredBy());
        r.setConverted(entity.getConverted());
        r.setCreatedAt(entity.getCreatedAt());

        // Attach region progress
        long count = preRegRepo.countByStateCodeAndMetroRegion(entity.getStateCode(), entity.getMetroRegion());
        int threshold = getThresholdForRegion(entity.getMetroRegion());
        r.setRegionSignupCount(count);
        r.setRegionThreshold(threshold);
        r.setRegionProgressPercent(Math.min(100.0, Math.round((count * 1000.0) / threshold) / 10.0));

        return r;
    }
}