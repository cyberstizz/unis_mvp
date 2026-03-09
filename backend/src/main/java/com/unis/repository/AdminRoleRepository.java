package com.unis.repository;

import com.unis.entity.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminRoleRepository extends JpaRepository<AdminRole, UUID> {

    @Query("SELECT ar FROM AdminRole ar WHERE ar.user.userId = :userId")
    Optional<AdminRole> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT ar FROM AdminRole ar ORDER BY ar.createdAt DESC")
    List<AdminRole> findAllRoles();

    @Query("SELECT CASE WHEN COUNT(ar) > 0 THEN true ELSE false END " +
           "FROM AdminRole ar WHERE ar.user.userId = :userId " +
           "AND ar.roleLevel IN :levels")
    boolean hasAnyRole(@Param("userId") UUID userId, @Param("levels") List<String> levels);

    void deleteByUser_UserId(UUID userId);
}