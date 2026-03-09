package com.unis.repository;

import com.unis.entity.ModerationAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {

    Page<ModerationAction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT ma FROM ModerationAction ma WHERE ma.performedBy.userId = :adminId ORDER BY ma.createdAt DESC")
    Page<ModerationAction> findByPerformedBy(@Param("adminId") UUID adminId, Pageable pageable);

    Page<ModerationAction> findByActionTypeOrderByCreatedAtDesc(String actionType, Pageable pageable);

    @Query("SELECT ma FROM ModerationAction ma WHERE ma.targetType = :targetType " +
           "AND ma.targetId = :targetId ORDER BY ma.createdAt DESC")
    List<ModerationAction> findByTarget(@Param("targetType") String targetType,
                                         @Param("targetId") UUID targetId);
}