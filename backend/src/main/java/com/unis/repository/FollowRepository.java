package com.unis.repository;

import com.unis.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface FollowRepository extends JpaRepository<Follow, UUID> {
    long countByFollowed_UserId(UUID followedId);
    long countByFollower_UserId(UUID followerId);
    boolean existsByFollower_UserIdAndFollowed_UserId(UUID followerId, UUID followedId);
    void deleteByFollower_UserIdAndFollowed_UserId(UUID followerId, UUID followedId);
}