package com.unis.repository;

import com.unis.entity.PreRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface PreRegistrationRepository extends JpaRepository<PreRegistration, Long> {

    // ─── Lookups ───
    Optional<PreRegistration> findByEmail(String email);
    Optional<PreRegistration> findByUsername(String username);
    Optional<PreRegistration> findByReferralCode(String referralCode);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByReferralCode(String referralCode);

    // ─── Region counts ───
    long countByStateCodeAndMetroRegion(String stateCode, String metroRegion);
    long countByStateCode(String stateCode);
    long countByConverted(boolean converted);

    @Query("SELECT COUNT(p) FROM PreRegistration p WHERE p.userType = :userType")
    long countByUserType(@Param("userType") String userType);

    // ─── Region breakdown for admin dashboard ───
    @Query("SELECT p.stateCode AS stateCode, p.metroRegion AS metroRegion, COUNT(p) AS cnt " +
           "FROM PreRegistration p WHERE p.converted = false " +
           "GROUP BY p.stateCode, p.metroRegion ORDER BY cnt DESC")
    List<Object[]> findRegionCounts();

    // ─── Top 10 regions ───
    @Query(value = "SELECT state_code, state_name, metro_region, COUNT(*) as cnt " +
                   "FROM pre_registrations WHERE converted = false " +
                   "GROUP BY state_code, state_name, metro_region " +
                   "ORDER BY cnt DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findTopRegions();

    // ─── Daily signup trend (last N days) ───
    @Query(value = "SELECT DATE(created_at) AS signup_date, COUNT(*) AS cnt " +
                   "FROM pre_registrations " +
                   "WHERE created_at >= :since " +
                   "GROUP BY DATE(created_at) ORDER BY signup_date", nativeQuery = true)
    List<Object[]> findDailySignups(@Param("since") LocalDateTime since);

    // ─── Signups by state (for the US map) ───
    @Query("SELECT p.stateCode, COUNT(p) FROM PreRegistration p GROUP BY p.stateCode")
    List<Object[]> findCountsByState();

    // ─── Referral leaderboard (waitlist only) ───
    @Query(value = "SELECT r.username, r.referral_code, r.state_code, r.metro_region, COUNT(ref.id) AS ref_count " +
                   "FROM pre_registrations r " +
                   "LEFT JOIN pre_registrations ref ON ref.referred_by = r.referral_code " +
                   "GROUP BY r.id, r.username, r.referral_code, r.state_code, r.metro_region " +
                   "HAVING COUNT(ref.id) > 0 " +
                   "ORDER BY ref_count DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findTopReferrers();

    // ─── Count signups today ───
    @Query("SELECT COUNT(p) FROM PreRegistration p WHERE p.createdAt >= :startOfDay")
    long countSignupsToday(@Param("startOfDay") LocalDateTime startOfDay);

    // ─── All pre-regs for a region (for activation/conversion) ───
    List<PreRegistration> findByStateCodeAndMetroRegionAndConvertedFalse(String stateCode, String metroRegion);

    // ─── Count referrals for a specific code ───
    long countByReferredBy(String referralCode);
}