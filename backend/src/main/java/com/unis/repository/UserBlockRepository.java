package com.unis.repository;

import com.unis.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    void deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    // Blocked accounts list in message settings, newest first.
    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(UUID blockerId);
}
