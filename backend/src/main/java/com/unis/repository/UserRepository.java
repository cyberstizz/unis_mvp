package com.unis.repository;

import com.unis.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    User findByUsername(String username);  // For auth

    // Replace the existing findByIdWithJurisdiction
@Query("SELECT u FROM User u LEFT JOIN FETCH u.jurisdiction WHERE u.userId = :id")  
Optional<User> findByIdWithJurisdiction(@Param("id") UUID id);  

    @Query("SELECT u FROM User u WHERE u.role = 'artist' AND u.jurisdiction.jurisdictionId = :jurisdictionId ORDER BY u.score DESC")
    List<User> findTopArtistsByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId, int limit);

    // For score batch: Native for complex SUMs (only one definition)
    @Query(value = "SELECT u.user_id, " +
                   "COALESCE((SELECT COUNT(r.referral_id) * 5 FROM referrals r WHERE r.referrer_id = u.user_id), 0) + " +
                   "COALESCE((SELECT COUNT(sp.play_id) * 1 FROM song_plays sp WHERE sp.user_id = u.user_id), 0) + " +
                   "COALESCE((SELECT COUNT(v.vote_id) * 2 FROM votes v WHERE v.user_id = u.user_id), 0) + " +
                   "FLOOR(DATEDIFF(CURRENT_DATE, u.created_at) / 30.0) * 1 + " +  // Age months *1
                   "u.score as new_score " +
                   "FROM users u " +
                   "GROUP BY u.user_id", nativeQuery = true)
    List<Object[]> computeUserScores();  // Returns [userId, newScore] arrays

    @Modifying
    @Query("UPDATE User u SET u.score = :score, u.level = :level WHERE u.userId = :id")
    void updateUserScoreAndLevel(@Param("id") UUID id, @Param("score") int score, @Param("level") String level);

    @Modifying
    @Query("UPDATE User u SET u.score = u.score + :increment WHERE u.userId = :id")
    void incrementScore(@Param("id") UUID id, @Param("increment") int increment);
}