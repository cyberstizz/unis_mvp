package com.unis.service;

import com.unis.entity.User;
import com.unis.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Referral codes: the one place that decides what a typed code means.
 *
 * Two kinds of code reach sign-up:
 *   - A member's own code, made at sign-up (USERNAME-X7K2Q).
 *   - A short code an admin creates for a campaign or partner (HARLEM, LEGEND),
 *     stored in referral_short_codes and tied to an owner account. Whoever
 *     owns the short code is the referrer, exactly as if their own code had
 *     been typed.
 *
 * Codes are matched without regard to case ("harlem" works), and the exact
 * code used is saved on the referral (referrals.code_used), so admin can count
 * sign-ups per code, not just per referrer.
 *
 * A short code is never reused: deactivating one keeps the row, so the same
 * letters can't later point somewhere else.
 */
@Service
public class ReferralCodeService {

    private static final Logger log = LoggerFactory.getLogger(ReferralCodeService.class);

    /** The original launch code still validates on the sign-up form; it has no owner. */
    public static final String LAUNCH_CODE = "UNIS-LAUNCH-2024";

    /** 3 to 20 characters: letters, digits and hyphens, starting with a letter or digit. */
    private static final Pattern SHORT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,19}$");

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    public ReferralCodeService(JdbcTemplate jdbc, UserRepository userRepository) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
    }

    /** What a valid code points at. shortCode is true for admin-made codes. */
    public record Resolved(User owner, String code, boolean shortCode) {}

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    /**
     * The account a code belongs to, if the code is valid. Deleted accounts
     * and switched-off short codes refer nobody.
     */
    public Optional<Resolved> resolve(String raw) {
        String code = normalize(raw);
        if (code.isEmpty() || code.length() > 64) return Optional.empty();

        Optional<User> member = userRepository.findByReferralCode(code).filter(u -> u.getDeletedAt() == null);
        if (member.isPresent()) return Optional.of(new Resolved(member.get(), code, false));

        List<UUID> owners = jdbc.query(
                "SELECT owner_user_id FROM referral_short_codes WHERE code = ? AND active = TRUE",
                (rs, i) -> rs.getObject(1, UUID.class), code);
        if (owners.isEmpty()) return Optional.empty();
        return userRepository.findById(owners.get(0))
                .filter(u -> u.getDeletedAt() == null)
                .map(u -> new Resolved(u, code, true));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Admin
    // ═════════════════════════════════════════════════════════════════════════

    /** Creates a short code owned by the account with this username. */
    public Map<String, Object> create(String rawCode, String ownerUsername, String rawLabel, UUID createdBy) {
        String code = normalize(rawCode);
        if (!SHORT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Use 3 to 20 letters, numbers or hyphens, like HARLEM or LENOX-CUTS.");
        }
        if (LAUNCH_CODE.equals(code)) throw new IllegalArgumentException("That code is reserved.");
        if (userRepository.existsByReferralCode(code)) {
            throw new IllegalArgumentException("That's already a member's code. Pick another.");
        }
        String username = ownerUsername == null ? "" : ownerUsername.trim();
        if (username.startsWith("@")) username = username.substring(1);
        if (username.isEmpty()) throw new IllegalArgumentException("Enter the username of the account that owns this code.");
        List<UUID> ownerIds = jdbc.query(
                "SELECT user_id FROM users WHERE LOWER(username) = LOWER(?) AND deleted_at IS NULL",
                (rs, i) -> rs.getObject(1, UUID.class), username);
        if (ownerIds.isEmpty()) throw new IllegalArgumentException("No account has the username " + username + ".");
        String label = rawLabel == null ? null : rawLabel.trim();
        if (label != null && label.length() > 80) label = label.substring(0, 80);
        if (label != null && label.isEmpty()) label = null;

        try {
            jdbc.update(
                    "INSERT INTO referral_short_codes (code, owner_user_id, label, created_by) VALUES (?, ?, ?, ?)",
                    code, ownerIds.get(0), label, createdBy);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("The code " + code + " already exists.");
        }
        log.info("[ReferralCodes] action=create code={} owner={} by={}", code, ownerIds.get(0), createdBy);
        return list().stream().filter(r -> code.equals(r.get("code"))).findFirst().orElse(Map.of("code", code));
    }

    /** Every short code with its owner and sign-up counts, newest first. */
    public List<Map<String, Object>> list() {
        return jdbc.query(
                "SELECT c.code, c.label, c.active, c.created_at, u.username AS owner, " +
                "  (SELECT COUNT(*) FROM referrals r JOIN users n ON n.user_id = r.referred_id AND n.deleted_at IS NULL " +
                "     WHERE r.code_used = c.code) AS total, " +
                "  (SELECT COUNT(*) FROM referrals r JOIN users n ON n.user_id = r.referred_id AND n.deleted_at IS NULL " +
                "     WHERE r.code_used = c.code AND r.created_at >= NOW() - INTERVAL '7 days') AS last7 " +
                "FROM referral_short_codes c JOIN users u ON u.user_id = c.owner_user_id " +
                "ORDER BY c.created_at DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("label", rs.getString("label"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("owner", rs.getString("owner"));
                    m.put("createdAt", rs.getTimestamp("created_at") == null ? null
                            : rs.getTimestamp("created_at").toInstant().toString());
                    m.put("total", rs.getLong("total"));
                    m.put("last7", rs.getLong("last7"));
                    return m;
                });
    }

    /** Switches a short code on or off. The row stays, so the code is never reused. */
    public void setActive(String rawCode, boolean active, UUID by) {
        String code = normalize(rawCode);
        int n = jdbc.update("UPDATE referral_short_codes SET active = ? WHERE code = ?", active, code);
        if (n == 0) throw new IllegalArgumentException("No short code " + code + ".");
        log.info("[ReferralCodes] action={} code={} by={}", active ? "activate" : "deactivate", code, by);
    }

    /**
     * Sign-ups per code (short codes and members' own codes): the last day,
     * 7 days, 30 days and all time. Referrals made before codes were recorded
     * count under the referrer's own code.
     */
    public List<Map<String, Object>> report() {
        return jdbc.query(
                "SELECT COALESCE(r.code_used, ref.referral_code) AS code, ref.username AS owner, " +
                "  (sc.code IS NOT NULL) AS short_code, " +
                "  COUNT(*) FILTER (WHERE r.created_at >= NOW() - INTERVAL '1 day') AS last1, " +
                "  COUNT(*) FILTER (WHERE r.created_at >= NOW() - INTERVAL '7 days') AS last7, " +
                "  COUNT(*) FILTER (WHERE r.created_at >= NOW() - INTERVAL '30 days') AS last30, " +
                "  COUNT(*) AS total " +
                "FROM referrals r " +
                "JOIN users ref ON ref.user_id = r.referrer_id " +
                "JOIN users n ON n.user_id = r.referred_id AND n.deleted_at IS NULL " +
                "LEFT JOIN referral_short_codes sc ON sc.code = r.code_used " +
                "GROUP BY 1, 2, 3 " +
                "ORDER BY last7 DESC, total DESC, code " +
                "LIMIT 300",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("owner", rs.getString("owner"));
                    m.put("shortCode", rs.getBoolean("short_code"));
                    m.put("last1", rs.getLong("last1"));
                    m.put("last7", rs.getLong("last7"));
                    m.put("last30", rs.getLong("last30"));
                    m.put("total", rs.getLong("total"));
                    return m;
                });
    }
}
