package com.unis.repository;

import com.unis.entity.ArtistTeamMember;
import com.unis.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtistTeamMemberRepository extends JpaRepository<ArtistTeamMember, UUID> {

    List<ArtistTeamMember> findByArtistIdOrderByCreatedAtAsc(UUID artistId);

    List<ArtistTeamMember> findByMemberIdAndStatusOrderByCreatedAtDesc(UUID memberId, ArtistTeamMember.Status status);

    Optional<ArtistTeamMember> findByArtistIdAndMemberId(UUID artistId, UUID memberId);

    long countByArtistId(UUID artistId);

    /** A live account by username, any capitalization (usernames are reserved case-insensitively). */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:name) AND u.deletedAt IS NULL")
    Optional<User> findLiveUserByUsername(@Param("name") String name);
}
