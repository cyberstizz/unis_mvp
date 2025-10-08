package com.unis.repository;

import com.unis.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Like, UUID> {
    @Query("SELECT COUNT(l) FROM Like l WHERE l.mediaType = :mediaType AND l.mediaId = :mediaId")
    Long countByMedia(@Param("mediaType") String mediaType, @Param("mediaId") UUID mediaId);

    boolean existsByUserIdAndMediaTypeAndMediaId(UUID userId, String mediaType, UUID mediaId);  // For toggle
}