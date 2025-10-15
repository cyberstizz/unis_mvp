package com.unis.repository;

import com.unis.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Like, UUID> {
    // Updated method to correctly access the userId in the User entity through the user field
    boolean existsByUser_UserIdAndMediaTypeAndMediaId(UUID userId, String mediaType, UUID mediaId);
}
